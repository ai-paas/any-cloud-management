// DO 의 네트워킹은 매우 단순: VPC 1개 + droplet-level Firewall. Firewall 은 instance 가 만들어진 뒤
// 만들어야 하므로 (DropletIds 필요) orchestrator (digitalocean.go) 가 별도로 처리. 본 파일은 VPC 만.
package digitalocean

import (
	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	do "github.com/pulumi/pulumi-digitalocean/sdk/v4/go/digitalocean"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// DoNetwork — DO VPC only. Firewall 은 instance 생성 후 orchestrator 가 처리.
type DoNetwork struct {
	Vpc *do.Vpc
}

func (n *DoNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	vpc, err := do.NewVpc(ctx, resourceName(spec, "vpc"), &do.VpcArgs{
		Name:    pulumi.String(resourceName(spec, "vpc")),
		Region:  pulumi.String(spec.Region),
		IpRange: pulumi.String(spec.VpcCidr),
	})
	if err != nil {
		return nil, err
	}

	n.Vpc = vpc

	// DO 는 SG 가 없음 — Firewall 이 droplet ID 기반. SubnetIDs 도 없음 (VPC 안에 subnet 개념 X).
	return &provisioner.NetworkOutput{
		VpcID:         vpc.ID(),
		SubnetIDs:     []pulumi.IDOutput{vpc.ID()}, // dummy: VPC ID 를 placeholder 로 (InstanceProvisioner 가 무시)
		SecurityGroup: pulumi.IDOutput{},
	}, nil
}
