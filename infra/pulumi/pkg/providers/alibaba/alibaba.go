// Package alibaba implements the Alibaba Cloud (ECS / VPC / RAM) provisioner.
//
// Orchestrator: AlibabaNetwork → image lookup → SSH keypair → RAM Role + policy attachments →
// AlibabaInstance loop. RAM Role 은 CCM 의 ECS/VPC/SLB 조작에 필요.
package alibaba

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"anycloud/infra/pulumi/pkg/userdata"
	alicloud "github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud"
	"github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud/ecs"
	"github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud/ram"
	"github.com/pulumi/pulumi-tls/sdk/v5/go/tls"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type Provisioner struct{}

func New() *Provisioner {
	return &Provisioner{}
}

func (p *Provisioner) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (pulumi.Map, error) {
	spec = model.ApplyProviderDefaults(spec)

	// 1) Network.
	module := NewModule()
	_, err := module.Network().Provision(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 2) Image lookup — spec.OsImage 면 nodeSpec 단계에서 override, default 는 Ubuntu 24.
	imageID, err := resolveDefaultImage(ctx, spec)
	if err != nil {
		return nil, err
	}

	// 3) SSH keypair (ECS native).
	privateKey, err := tls.NewPrivateKey(ctx, resourceName(spec, "ssh-key"), &tls.PrivateKeyArgs{
		Algorithm: pulumi.String("RSA"),
		RsaBits:   pulumi.Int(4096),
	})
	if err != nil {
		return nil, err
	}
	keyPair, err := ecs.NewKeyPair(ctx, resourceName(spec, "keypair"), &ecs.KeyPairArgs{
		KeyName:   pulumi.String(resourceName(spec, "keypair")),
		PublicKey: privateKey.PublicKeyOpenssh,
	})
	if err != nil {
		return nil, err
	}

	// 4) RAM Role + policy attachments — CCM 권한. PoC 광범위, 운영에서 좁힐 것.
	ramRole, err := ram.NewRole(ctx, resourceName(spec, "ccm-role"), &ram.RoleArgs{
		Name:        pulumi.String(resourceName(spec, "ccm-role")),
		Description: pulumi.String("Anycloud K8s cloud-controller-manager role"),
		Document: pulumi.String(`{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {"Service": ["ecs.aliyuncs.com"]}
    }
  ],
  "Version": "1"
}`),
		Force: pulumi.Bool(true),
	})
	if err != nil {
		return nil, err
	}
	for _, policyName := range []string{"AliyunECSFullAccess", "AliyunVPCFullAccess", "AliyunSLBFullAccess"} {
		_, attachErr := ram.NewRolePolicyAttachment(ctx,
			resourceName(spec, "ccm-role-"+policyName),
			&ram.RolePolicyAttachmentArgs{
				PolicyName: pulumi.String(policyName),
				PolicyType: pulumi.String("System"),
				RoleName:   ramRole.Name,
			})
		if attachErr != nil {
			return nil, attachErr
		}
	}

	module.SetInstanceContext(imageID, keyPair.KeyName, ramRole.Name)

	// 5) Instance loop.
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

	// 6) Outputs.
	outputs := pulumi.Map{
		"provider":         pulumi.String(model.CanonicalProviderName(spec.Provider)),
		"clusterName":      pulumi.String(spec.Name),
		"masterVmSpec":     pulumi.String(spec.MasterInstanceType),
		"workerVmSpec":     pulumi.String(spec.WorkerInstanceType),
		"osImage":          pulumi.String(model.ResolvedOsImage(spec)),
		"vpcId":            module.network.Vpc.ID(),
		"subnetId":         module.network.VSwitch.ID(),
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

// pickZone — VPC/VSwitch 지원 zone 첫번째. network.go 가 호출.
func pickZone(ctx *pulumi.Context) (string, error) {
	zones, err := alicloud.GetZones(ctx, &alicloud.GetZonesArgs{
		AvailableResourceCreation: stringPtr("VSwitch"),
		NetworkType:               stringPtr("Vpc"),
	}, nil)
	if err != nil {
		return "", err
	}
	if len(zones.Zones) == 0 {
		return "", fmt.Errorf("no Alibaba zones available for current account/region")
	}
	return zones.Zones[0].Id, nil
}

// resolveDefaultImage — Ubuntu 24 lookup. spec.OsImage 가 image ID 면 orchestrator 가 NodeSpec.OsImage 로
// 전달해 instance.go 가 그대로 사용. 본 lookup 은 fallback default.
func resolveDefaultImage(ctx *pulumi.Context, spec *model.ClusterSpec) (string, error) {
	if spec.OsImage != "" {
		return spec.OsImage, nil
	}
	images, err := ecs.GetImages(ctx, &ecs.GetImagesArgs{
		NameRegex:    pulumi.StringRef("ubuntu_24"),
		Architecture: pulumi.StringRef("x86_64"),
		OsType:       pulumi.StringRef("linux"),
	}, nil)
	if err != nil {
		return "", err
	}
	if len(images.Images) == 0 {
		return "", fmt.Errorf("no Alibaba Ubuntu 24 images found")
	}
	return images.Images[0].Id, nil
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

func stringPtr(value string) *string {
	return &value
}
