// GcpInstance 는 단일 compute.Instance + 전용 외부 IP (compute.Address) 한 쌍을 생성. AWS sample
// 과의 주요 차이:
//   - GCP 는 instance 별 static external IP 가 표준 (AWS 의 MapPublicIpOnLaunch 와 다름).
//   - Spot/preemptible 매핑: spec.UseSpot → Scheduling.Preemptible=true + AutomaticRestart=false.
//   - SecurityGroup 대신 NetworkTag 매칭으로 firewall scope 결정.
package gcp

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-gcp/sdk/v8/go/gcp/compute"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// GcpInstance — interface.InstanceProvisioner 구현.
//
// GCP-specific context (project, zones, image, network tag, SA email, SSH key) 는 caller 가 사전
// 주입 — 모든 instance 가 공유하므로 매 호출마다 생성하는 비용 회피.
type GcpInstance struct {
	Project             string
	Region              string
	Zones               []string
	ImageSelfLink       string
	NetworkTag          string
	SubnetworkID        pulumi.IDOutput
	ServiceAccountEmail pulumi.StringOutput
	SshPublicKey        pulumi.StringOutput
}

// Provision — NodeSpec → 외부 IP + compute.Instance 한 쌍.
//
// zone 분산: NodeSpec.Index 를 zones round-robin 으로 매핑 — node.SubnetIndex 는 GCP 에서 사용 안 함
// (regional subnet 1개이므로 무의미). 따라서 zone 인덱스는 NodeSpec.Index 기준.
func (g *GcpInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if g.Project == "" || len(g.Zones) == 0 || g.ImageSelfLink == "" || g.NetworkTag == "" {
		return nil, fmt.Errorf("GcpInstance: project/zones/image/networkTag must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)
	zone := g.Zones[node.Index%len(g.Zones)]

	address, err := compute.NewAddress(ctx, resourceName(spec, suffix+"-ip"), &compute.AddressArgs{
		Project: pulumi.String(g.Project),
		Region:  pulumi.String(g.Region),
	})
	if err != nil {
		return nil, err
	}

	instArgs := &compute.InstanceArgs{
		Project:     pulumi.String(g.Project),
		Zone:        pulumi.String(zone),
		MachineType: pulumi.String(node.InstanceType),
		Tags:        pulumi.StringArray{pulumi.String(g.NetworkTag)},
		BootDisk: &compute.InstanceBootDiskArgs{
			InitializeParams: &compute.InstanceBootDiskInitializeParamsArgs{
				Image: pulumi.String(g.ImageSelfLink),
				// 디스크 크기(GB). model.defaults 가 0 이하를 50 으로 정규화. NodeHasDiskPressure 방지.
				Size: pulumi.Int(node.RootDiskSizeGb),
			},
		},
		NetworkInterfaces: compute.InstanceNetworkInterfaceArray{
			&compute.InstanceNetworkInterfaceArgs{
				Subnetwork: g.SubnetworkID,
				AccessConfigs: compute.InstanceNetworkInterfaceAccessConfigArray{
					&compute.InstanceNetworkInterfaceAccessConfigArgs{
						NatIp: address.Address,
					},
				},
			},
		},
		Metadata: pulumi.StringMap{
			"ssh-keys":       pulumi.Sprintf("%s:%s", spec.SSHUser, g.SshPublicKey),
			"startup-script": toStringOutput(node.UserData),
		},
		ServiceAccount: &compute.InstanceServiceAccountArgs{
			Email:  g.ServiceAccountEmail,
			Scopes: pulumi.StringArray{pulumi.String("https://www.googleapis.com/auth/cloud-platform")},
		},
	}

	// Preemptible 매핑. master 는 NodeSpecsFor helper 가 UseSpot=false 강제.
	// AutomaticRestart=false 는 preemptible 의 hard requirement (true 이면 GCP 가 API 에러 반환).
	if node.UseSpot {
		instArgs.Scheduling = &compute.InstanceSchedulingArgs{
			Preemptible:       pulumi.Bool(true),
			AutomaticRestart:  pulumi.Bool(false),
			ProvisioningModel: pulumi.String("SPOT"),
		}
	}

	instance, err := compute.NewInstance(ctx, resourceName(spec, suffix), instArgs, opts...)
	if err != nil {
		return nil, err
	}

	return &provisioner.InstanceOutput{
		Resource:   instance,
		InstanceID: instance.ID(),
		PrivateIP:  instance.NetworkInterfaces.Index(pulumi.Int(0)).NetworkIp().Elem(),
		PublicIP:   address.Address,
	}, nil
}

// toStringOutput — pulumi.StringInput 을 StringOutput 으로 정규화. nil 안전.
func toStringOutput(s pulumi.StringInput) pulumi.StringOutput {
	if s == nil {
		return pulumi.String("").ToStringOutput()
	}
	return s.ToStringOutput()
}
