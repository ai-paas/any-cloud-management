// DO Droplet 은 단일 객체로 public/private IP 가 attribute 로 노출됨 (다른 cloud 처럼 별도 NIC/EIP
// 객체 없음). Spot/preemptible 미지원 — NodeSpec.UseSpot 은 no-op.
//
// OS image: node.OsImage 가 비어있지 않으면 그대로 (예: "ubuntu-22-04-x64"), 아니면 default
// "ubuntu-24-04-x64". DO 는 image slug 형식.
package digitalocean

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	do "github.com/pulumi/pulumi-digitalocean/sdk/v4/go/digitalocean"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

const defaultImage = "ubuntu-24-04-x64"

// DoInstance — interface.InstanceProvisioner 구현.
type DoInstance struct {
	Region      string
	VpcID       pulumi.IDOutput
	SshKeyID    pulumi.IDOutput
	Environment string
}

func (d *DoInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if d.Region == "" {
		return nil, fmt.Errorf("DoInstance: region must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)
	image := node.OsImage
	if image == "" {
		image = defaultImage
	}

	roleTag := "k8s-worker"
	if node.Role == provisioner.RoleMaster {
		roleTag = "k8s-master"
	}

	// 주: DigitalOcean Droplet 디스크는 size slug(node.InstanceType)에 종속 — 별도 root 디스크
	// 크기 지정 불가. node.RootDiskSizeGb 는 본 provider 에서 no-op (별도 Volume attach 필요).
	// 작은 slug 사용 시 disk pressure 가능 → slug 를 충분히 큰 것으로 선택할 것.
	droplet, err := do.NewDroplet(ctx, resourceName(spec, suffix), &do.DropletArgs{
		Name:       pulumi.String(resourceName(spec, suffix)),
		Region:     pulumi.String(d.Region),
		Size:       pulumi.String(node.InstanceType),
		Image:      pulumi.String(image),
		Monitoring: pulumi.Bool(false),
		Ipv6:       pulumi.Bool(false),
		VpcUuid:    d.VpcID.ToStringOutput(),
		SshKeys:    pulumi.StringArray{d.SshKeyID.ToStringOutput()},
		UserData:   node.UserData,
		Tags: pulumi.StringArray{
			pulumi.String(spec.Name),
			pulumi.String(d.Environment),
			pulumi.String(roleTag),
		},
	}, opts...)
	if err != nil {
		return nil, err
	}

	return &provisioner.InstanceOutput{
		Resource:   droplet,
		InstanceID: droplet.ID(),
		PrivateIP:  droplet.Ipv4AddressPrivate,
		PublicIP:   droplet.Ipv4Address,
	}, nil
}
