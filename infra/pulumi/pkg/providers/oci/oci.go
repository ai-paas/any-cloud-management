// Package oci implements the Oracle Cloud (Compute / VCN / IAM) provisioner.
//
// Orchestrator: OciNetwork (VCN/IGW/RT/SL/Subnet) → SSH key → AD lookup → CCM dynamic group +
// policy → OciInstance loop. CCM 권한은 OCI-only 이라 orchestrator 가 보유.
package oci

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	"github.com/pulumi/pulumi-oci/sdk/v3/go/oci/identity"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)
	if spec.Region == "" {
		return nil, fmt.Errorf("region is required for OCI provisioning")
	}
	if spec.OciCompartmentId == "" {
		return nil, fmt.Errorf("ociCompartmentId is required for OCI provisioning")
	}

	ads, err := identity.GetAvailabilityDomains(ctx, &identity.GetAvailabilityDomainsArgs{
		CompartmentId: spec.OciCompartmentId,
	}, nil)
	if err != nil {
		return nil, err
	}
	if len(ads.AvailabilityDomains) == 0 {
		return nil, fmt.Errorf("no OCI availability domains found for compartment")
	}
	adName := ads.AvailabilityDomains[0].Name

	// 1) Network.
	module := NewModule()
	_, err = module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) SSH keypair.
	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	// 3) OCI-only IAM — Dynamic Group + Policy 로 CCM 의 instance-principal 권한 부여.
	//    PoC 광범위 권한. 운영에선 minimal 로 좁힐 것.
	dynamicGroup, err := identity.NewDynamicGroup(ctx, resourceName(spec, "dyngroup"),
		&identity.DynamicGroupArgs{
			CompartmentId: pulumi.String(spec.OciCompartmentId),
			Description:   pulumi.String("Anycloud K8s cloud-controller-manager dynamic group"),
			MatchingRule: pulumi.String(
				fmt.Sprintf("ALL {instance.compartment.id = '%s'}", spec.OciCompartmentId)),
			Name: pulumi.String(resourceName(spec, "dyngroup")),
		})
	if err != nil {
		return nil, err
	}
	_, err = identity.NewPolicy(ctx, resourceName(spec, "ccm-policy"), &identity.PolicyArgs{
		CompartmentId: pulumi.String(spec.OciCompartmentId),
		Name:          pulumi.String(resourceName(spec, "ccm-policy")),
		Description:   pulumi.String("Allow K8s cloud-controller-manager to manage compute/network/blockstorage"),
		Statements: pulumi.StringArray{
			pulumi.Sprintf("Allow dynamic-group %s to manage instance-family in compartment id %s",
				dynamicGroup.Name, spec.OciCompartmentId),
			pulumi.Sprintf("Allow dynamic-group %s to manage virtual-network-family in compartment id %s",
				dynamicGroup.Name, spec.OciCompartmentId),
			pulumi.Sprintf("Allow dynamic-group %s to manage volume-family in compartment id %s",
				dynamicGroup.Name, spec.OciCompartmentId),
			pulumi.Sprintf("Allow dynamic-group %s to manage load-balancers in compartment id %s",
				dynamicGroup.Name, spec.OciCompartmentId),
		},
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(spec.OciCompartmentId, adName, privateKey.PublicKeyOpenssh)

	// 4) Instance loop.
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

	// 5) Outputs.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"vpcId":            module.network.Vcn.ID(),
		"subnetId":         module.network.Subnet.ID(),
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
