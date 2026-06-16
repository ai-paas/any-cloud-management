// Package digitalocean implements the DigitalOcean (Droplet / VPC) provisioner.
//
// Orchestrator: DoNetwork (VPC) → SSH key + DO SshKey resource → DoInstance loop → Firewall (droplet
// 별 ID 가 필요해 instance 생성 후 만든다).
package digitalocean

import (
	"fmt"
	"strconv"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	do "github.com/pulumi/pulumi-digitalocean/sdk/v4/go/digitalocean"
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
		return nil, fmt.Errorf("region is required for DigitalOcean provisioning")
	}

	// 1) Network — VPC.
	module := NewModule()
	_, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) SSH keypair + DO SshKey resource (droplet 에 attach).
	sshKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}

	keyPair, err := do.NewSshKey(ctx, resourceName(spec, "ssh-keypair"), &do.SshKeyArgs{
		Name:      pulumi.String(resourceName(spec, "ssh-keypair")),
		PublicKey: sshKey.PublicKeyOpenssh,
	})
	if err != nil {
		return nil, err
	}

	module.SetInstanceContext(spec.Region, spec.Environment, keyPair.ID())

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

	// 4) Firewall — droplet ID list 가 필요하므로 instance 생성 후 만든다.
	firewallDropletIds := pulumi.IntArray{asDropletID(masterOut.InstanceID)}
	for _, w := range workerOuts {
		firewallDropletIds = append(firewallDropletIds, asDropletID(w.InstanceID))
	}
	_, err = do.NewFirewall(ctx, resourceName(spec, "fw"), &do.FirewallArgs{
		Name:       pulumi.String(resourceName(spec, "fw")),
		DropletIds: firewallDropletIds,
		InboundRules: do.FirewallInboundRuleArray{
			&do.FirewallInboundRuleArgs{
				Protocol: pulumi.String("tcp"), PortRange: pulumi.String("22"),
				SourceAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
			&do.FirewallInboundRuleArgs{
				Protocol: pulumi.String("tcp"), PortRange: pulumi.String("6443"),
				SourceAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
			&do.FirewallInboundRuleArgs{
				Protocol: pulumi.String("tcp"), PortRange: pulumi.String("30000-32767"),
				SourceAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
			&do.FirewallInboundRuleArgs{
				Protocol:        pulumi.String("icmp"),
				SourceAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
		},
		OutboundRules: do.FirewallOutboundRuleArray{
			&do.FirewallOutboundRuleArgs{
				Protocol: pulumi.String("tcp"), PortRange: pulumi.String("1-65535"),
				DestinationAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
			&do.FirewallOutboundRuleArgs{
				Protocol: pulumi.String("udp"), PortRange: pulumi.String("1-65535"),
				DestinationAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
			&do.FirewallOutboundRuleArgs{
				Protocol:             pulumi.String("icmp"),
				DestinationAddresses: pulumi.StringArray{pulumi.String("0.0.0.0/0"), pulumi.String("::/0")},
			},
		},
	})
	if err != nil {
		return nil, err
	}

	// 5) Outputs.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"vpcId":            module.network.Vpc.ID(),
		"subnetId":         pulumi.String(""),
		"masterInstanceId": masterOut.InstanceID,
		"masterPublicIp":   masterOut.PublicIP,
		"masterPrivateIp":  masterOut.PrivateIP,
		"masterPublicDns":  pulumi.String(""),
		"apiServerUrl":     pulumi.Sprintf("https://%s:6443", masterOut.PublicIP),
		"sshPrivateKeyPem": pulumi.ToSecret(sshKey.PrivateKeyPem),
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

func asDropletID(id pulumi.IDOutput) pulumi.IntOutput {
	return id.ToStringOutput().ApplyT(func(v string) (int, error) {
		return strconv.Atoi(v)
	}).(pulumi.IntOutput)
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
