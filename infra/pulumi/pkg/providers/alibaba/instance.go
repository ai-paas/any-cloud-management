// Alibaba ECS Instance 는 단일 객체로 PublicIp/PrivateIp 가 attribute. Spot 매핑:
// SpotStrategy=SpotAsPriceGo + SpotDuration=0 (one-time). master 는 NodeSpecsFor 가 UseSpot=false 강제.
// OS image: node.OsImage 가 비어있지 않으면 그대로 (예: "ubuntu_24_04_x64_..."), 아니면 default lookup.
package alibaba

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud/ecs"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AlibabaInstance — interface.InstanceProvisioner 구현.
type AlibabaInstance struct {
	DefaultImageID  string
	SecurityGroupID pulumi.IDOutput
	VSwitchID       pulumi.IDOutput
	KeypairName     pulumi.StringOutput
	RamRoleName     pulumi.StringOutput
}

func (a *AlibabaInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if a.DefaultImageID == "" {
		return nil, fmt.Errorf("AlibabaInstance: default image ID must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)
	image := a.DefaultImageID
	if node.OsImage != "" {
		image = node.OsImage
	}

	args := &ecs.InstanceArgs{
		InstanceName:       pulumi.String(resourceName(spec, suffix)),
		InstanceType:       pulumi.String(node.InstanceType),
		ImageId:            pulumi.String(image),
		VswitchId:          a.VSwitchID.ToStringOutput(),
		SecurityGroups:     pulumi.StringArray{a.SecurityGroupID.ToStringOutput()},
		KeyName:            a.KeypairName,
		RoleName:           a.RamRoleName,
		SystemDiskCategory: pulumi.String("cloud_essd"),
		// system 디스크 크기(GB). model.defaults 가 0 이하를 50 으로 정규화. NodeHasDiskPressure 방지.
		SystemDiskSize:          pulumi.Int(node.RootDiskSizeGb),
		InternetMaxBandwidthOut: pulumi.Int(10),
		UserData:                node.UserData,
	}

	// Spot — Alibaba SpotAsPriceGo = 시장가 추종 (max price 제약 없음).
	if node.UseSpot {
		args.SpotStrategy = pulumi.String("SpotAsPriceGo")
		args.SpotDuration = pulumi.Int(0) // 0 = no time limit (one-time interruption only).
	}

	instance, err := ecs.NewInstance(ctx, resourceName(spec, suffix), args, opts...)
	if err != nil {
		return nil, err
	}
	return &provisioner.InstanceOutput{
		Resource:   instance,
		InstanceID: instance.ID(),
		PrivateIP:  instance.PrivateIp,
		PublicIP:   instance.PublicIp,
	}, nil
}
