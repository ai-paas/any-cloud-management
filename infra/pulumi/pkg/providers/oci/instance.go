// OCI 의 instance 는 CreateVnicDetails 안에 SubnetId/AssignPublicIp 를 inline 으로 가진다 (AWS 의
// 분리된 NIC + EIP 와 다름). Spot/preemptible: OCI 는 일반 shape 에서 미지원 — NodeSpec.UseSpot 은
// no-op 으로 처리 (단순화).
//
// OS image: node.OsImage 가 "ocid1." 으로 시작하면 SourceImageId 로 직접 사용, 아니면 default
// filter (Canonical Ubuntu 24.04).
package oci

import (
	"encoding/base64"
	"fmt"
	"strings"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-oci/sdk/v3/go/oci/core"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OciInstance — interface.InstanceProvisioner 구현.
type OciInstance struct {
	CompartmentId      string
	AvailabilityDomain string
	SshPublicKey       pulumi.StringOutput
	SubnetID           pulumi.IDOutput
}

func (o *OciInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if o.CompartmentId == "" || o.AvailabilityDomain == "" {
		return nil, fmt.Errorf("OciInstance: compartmentId/availabilityDomain must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)
	src := resolveSource(o.CompartmentId, node.OsImage, node.RootDiskSizeGb)

	instance, err := core.NewInstance(ctx, resourceName(spec, suffix), &core.InstanceArgs{
		AvailabilityDomain: pulumi.String(o.AvailabilityDomain),
		CompartmentId:      pulumi.String(o.CompartmentId),
		DisplayName:        pulumi.String(resourceName(spec, suffix)),
		Shape:              pulumi.String(node.InstanceType),
		Metadata: pulumi.StringMap{
			"sshAuthorizedKeys": o.SshPublicKey,
			"userData":          base64String(node.UserData),
		},
		CreateVnicDetails: &core.InstanceCreateVnicDetailsArgs{
			SubnetId:       o.SubnetID.ToStringOutput(),
			AssignPublicIp: pulumi.StringPtr("true"),
			DisplayName:    pulumi.String(resourceName(spec, suffix+"-vnic")),
		},
		SourceDetails: src,
	}, opts...)
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

// resolveSource — node.OsImage 가 OCID 면 SourceImageId, 아니면 default Ubuntu 24.04 filter.
// bootVolumeGb: boot volume 크기(GB). model.defaults 가 0 이하를 50 으로 정규화 (OCI 최소 50).
// 미설정 시 image 기본(~47GB)으로 kubelet ephemeral-storage eviction (NodeHasDiskPressure) 위험.
func resolveSource(compartmentId, override string, bootVolumeGb int) *core.InstanceSourceDetailsArgs {
	if strings.HasPrefix(override, "ocid1.") {
		return &core.InstanceSourceDetailsArgs{
			SourceType:          pulumi.String("image"),
			SourceId:            pulumi.StringPtr(override),
			BootVolumeSizeInGbs: pulumi.StringPtr(fmt.Sprintf("%d", bootVolumeGb)),
		}
	}
	return &core.InstanceSourceDetailsArgs{
		SourceType:          pulumi.String("image"),
		BootVolumeSizeInGbs: pulumi.StringPtr(fmt.Sprintf("%d", bootVolumeGb)),
		InstanceSourceImageFilterDetails: &core.InstanceSourceDetailsInstanceSourceImageFilterDetailsArgs{
			CompartmentId:          pulumi.String(compartmentId),
			OperatingSystem:        pulumi.StringPtr("Canonical Ubuntu"),
			OperatingSystemVersion: pulumi.StringPtr("24.04"),
		},
	}
}

func base64String(input pulumi.StringInput) pulumi.StringOutput {
	if input == nil {
		return pulumi.String("").ToStringOutput()
	}
	return input.ToStringOutput().ApplyT(func(v string) string {
		return base64.StdEncoding.EncodeToString([]byte(v))
	}).(pulumi.StringOutput)
}
