// Package openstack implements the OpenStack (Nova / Neutron / Keystone) provisioner.
//
// Orchestrator: OpenstackNetwork → SSH keypair → OpenstackInstance loop. instance 1 대 = 4 자원
// (Port + Instance + FloatingIp + Assoc) — InstanceProvisioner 내부에 캡슐화.
package openstack

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/pulumi/pulumi-openstack/sdk/v5/go/openstack/compute"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)

	if spec.OpenstackExternalNetworkId == "" {
		return nil, fmt.Errorf("openstackExternalNetworkId is required for OpenStack provisioning")
	}
	if spec.OpenstackFloatingIpPool == "" {
		return nil, fmt.Errorf("openstackFloatingIpPool is required for OpenStack provisioning")
	}

	// 1) Network.
	module := NewModule()
	_, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) SSH keypair (OpenStack 자체 형식).
	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	keypair, err := compute.NewKeypair(ctx, resourceName(spec, "keypair"), &compute.KeypairArgs{
		Name:      pulumi.String(resourceName(spec, "keypair")),
		PublicKey: privateKey.PublicKeyOpenssh,
		Region:    pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(spec.Region, spec.OpenstackFloatingIpPool,
		spec.OpenstackImageName, spec.OpenstackFlavorName,
		keypair.Name)

	// 3) Instance loop.
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

	// 4) Outputs.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"networkId":        module.network.Network.ID(),
		"subnetId":         module.network.Subnet.ID(),
		"routerId":         module.network.Router.ID(),
		"masterInstanceId": masterOut.InstanceID,
		"masterPublicIp":   masterOut.PublicIP,
		"masterPrivateIp":  masterOut.PrivateIP,
		"masterPublicDns":  pulumi.String(""),
		"apiServerUrl":     pulumi.Sprintf("https://%s:6443", masterOut.PublicIP),
		"sshPrivateKeyPem": pulumi.ToSecret(privateKey.PrivateKeyPem),
		"masterSshCommand": pulumi.Sprintf("ssh -i ./secrets/%s.pem %s@%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP),
		"kubeconfigRemotePath": pulumi.String("/etc/kubernetes/admin.conf"),
		"kubeconfigFetchCommand": pulumi.Sprintf(
			"ssh -i ./secrets/%s.pem %s@%s \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-%s",
			spec.Name, spec.SSHUser, masterOut.PublicIP, spec.Name,
		),
		"nodes": buildNodeArray(spec, masterOut, workerOuts),
	}

	return outputs, nil
}

func buildNodeArray(spec *model.ClusterSpec, master *provisioner.InstanceOutput,
	workers []*provisioner.InstanceOutput) pulumi.Array {
	nodes := pulumi.Array{
		pulumi.Map{
			"role":       pulumi.String("master"),
			"instanceId": master.InstanceID,
			"publicIp":   master.PublicIP,
			"privateIp":  master.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf("ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, master.PublicIP),
		},
	}
	for i, worker := range workers {
		nodes = append(nodes, pulumi.Map{
			"role":       pulumi.String(fmt.Sprintf("worker-%d", i+1)),
			"instanceId": worker.InstanceID,
			"publicIp":   worker.PublicIP,
			"privateIp":  worker.PrivateIP,
			"publicDns":  pulumi.String(""),
			"ssh": pulumi.Sprintf("ssh -i ./secrets/%s.pem %s@%s",
				spec.Name, spec.SSHUser, worker.PublicIP),
		})
	}
	return nodes
}

func resourceName(spec *model.ClusterSpec, suffix string) string {
	return model.JoinResourceName(spec.Name, suffix)
}
