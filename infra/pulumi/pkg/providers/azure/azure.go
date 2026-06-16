// Package azure implements the Azure (VM / VNet / Managed Identity) provisioner.
//
// Orchestrator: AzureNetwork (RG/VNet/Subnet/NSG) → SSH key → AzureInstance loop. RG 가 모든
// 자원의 컨테이너이므로 network.go 가 RG 까지 책임지고, instance.go 는 RG handle 만 받아 사용.
package azure

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)

	if spec.AzureResourceGroup == "" {
		return nil, fmt.Errorf("azureResourceGroup is required for Azure provisioning")
	}
	if spec.Region == "" {
		return nil, fmt.Errorf("region is required for Azure provisioning")
	}

	// 1) Network — RG / VNet / Subnet / NSG.
	module := NewModule()
	_, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) SSH keypair — VM 별로 공유.
	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(spec.Region, spec.SSHUser, privateKey.PublicKeyOpenssh)

	// 3) Instance loop — NodeSpecsFor → role 별 UserData → AzureInstance.Provision.
	nodeSpecs := provisioner.NodeSpecsFor(spec)
	for i := range nodeSpecs {
		if nodeSpecs[i].Role == provisioner.RoleMaster {
			nodeSpecs[i].UserData = userdata.Master(spec)
		} else {
			nodeSpecs[i].UserData = userdata.Worker(spec)
		}
	}

	var masterOut *provisioner.InstanceOutput
	workerOuts := make([]*provisioner.InstanceOutput, 0, spec.WorkerCount)
	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleMaster {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n)
		if perr != nil {
			return nil, perr
		}
		masterOut = out
	}
	if masterOut == nil {
		return nil, fmt.Errorf("no master NodeSpec produced — masterCount=%d", spec.MasterCount)
	}

	for _, n := range nodeSpecs {
		if n.Role != provisioner.RoleWorker {
			continue
		}
		out, perr := module.Instance().Provision(ctx, spec, nil, n,
			pulumi.DependsOn([]pulumi.Resource{masterOut.Resource}))
		if perr != nil {
			return nil, perr
		}
		workerOuts = append(workerOuts, out)
	}

	// 4) Outputs — backend 가 의존하는 기존 key 셋 그대로.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"resourceGroupName": module.network.ResourceGroup.Name,
		"virtualNetworkId": module.network.VirtualNetwork.ID(),
		"subnetId":         module.network.Subnet.ID(),
		"masterInstanceId": masterOut.InstanceID,
		"masterPublicIp":   masterOut.PublicIP,
		"masterPrivateIp":  masterOut.PrivateIP,
		"masterPublicDns":  pulumi.String(""),
		"apiServerUrl":     pulumi.Sprintf("https://%s:6443", masterOut.PublicIP),
		"sshPrivateKeyPem": pulumi.ToSecret(privateKey.PrivateKeyPem),
		"masterSshCommand": pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP,
		),
		"kubeconfigRemotePath": pulumi.String("/etc/kubernetes/admin.conf"),
		"kubeconfigFetchCommand": pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP, spec.Name,
		),
		"nodes": buildNodeArray(spec, masterOut, workerOuts),
	}

	return outputs, nil
}

// buildNodeArray — backend 출력 호환.
func buildNodeArray(spec *model.ClusterSpec, master *provisioner.InstanceOutput,
	workers []*provisioner.InstanceOutput) pulumi.Array {
	nodes := pulumi.Array{
		pulumi.Map{
			"role":       pulumi.String("master"),
			"instanceId": master.InstanceID,
			"publicIp":   master.PublicIP,
			"privateIp":  master.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, master.PublicIP,
			),
		},
	}

	for i, worker := range workers {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String(fmt.Sprintf("worker-%d", i+1)),
			"instanceId": worker.InstanceID,
			"publicIp":   worker.PublicIP,
			"privateIp":  worker.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf(
				"ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, worker.PublicIP,
			),
		})
	}

	return nodes
}

func resourceName(spec *model.ClusterSpec, suffix string) string {
	return model.JoinResourceName(spec.Name, suffix)
}
