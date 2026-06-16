// OpenStack 의 instance 1 대는 4-step:
//  1. Port (NIC, security group 연결)
//  2. Instance (compute) — userdata + flavor + image + keypair
//  3. FloatingIp 발급
//  4. FloatingIp Associate (port 에 attach)
//
// Spot/preemptible: OpenStack 표준엔 미지원. NodeSpec.UseSpot 은 no-op.
// OS image: node.OsImage 가 비어있지 않으면 그대로, 아니면 spec.OpenstackImageName.
// Flavor: 모든 노드가 spec.OpenstackFlavorName (기존 동작 보존). node.InstanceType 은 무시.
package openstack

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-openstack/sdk/v5/go/openstack/compute"
	"github.com/pulumi/pulumi-openstack/sdk/v5/go/openstack/networking"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OpenstackInstance — interface.InstanceProvisioner 구현. 한 호출 = (Port, Instance, FloatingIp, Assoc) 4개.
type OpenstackInstance struct {
	Region            string
	NetworkID         pulumi.IDOutput
	SubnetID          pulumi.IDOutput
	SecurityGroupID   pulumi.IDOutput
	SecurityGroupName pulumi.StringOutput
	KeypairName       pulumi.StringOutput
	FloatingIpPool    string
	DefaultImageName  string
	DefaultFlavorName string
}

func (o *OpenstackInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if o.Region == "" || o.FloatingIpPool == "" {
		return nil, fmt.Errorf("OpenstackInstance: region/floatingIpPool must be set before Provision")
	}

	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)

	port, err := networking.NewPort(ctx, resourceName(spec, suffix+"-port"), &networking.PortArgs{
		Name:         pulumi.String(resourceName(spec, suffix+"-port")),
		NetworkId:    o.NetworkID.ToStringOutput(),
		AdminStateUp: pulumi.Bool(true),
		FixedIps: networking.PortFixedIpArray{
			&networking.PortFixedIpArgs{SubnetId: o.SubnetID.ToStringOutput()},
		},
		SecurityGroupIds: pulumi.StringArray{o.SecurityGroupID.ToStringOutput()},
		Region:           pulumi.String(o.Region),
	})
	if err != nil {
		return nil, err
	}

	imageName := o.DefaultImageName
	if node.OsImage != "" {
		imageName = node.OsImage
	}

	// 주: OpenStack 인스턴스 디스크는 flavor(FlavorName)의 disk 속성에 종속 — node.RootDiskSizeGb 는
	// 본 provider 에서 no-op (별도 root 디스크 크기 지정은 boot-from-volume = Cinder 볼륨 필요).
	// disk pressure 방지를 위해 충분한 disk 를 가진 flavor 를 선택할 것.
	instance, err := compute.NewInstance(ctx, resourceName(spec, suffix), &compute.InstanceArgs{
		Name:           pulumi.String(resourceName(spec, suffix)),
		ImageName:      pulumi.String(imageName),
		FlavorName:     pulumi.String(o.DefaultFlavorName),
		KeyPair:        o.KeypairName,
		SecurityGroups: pulumi.StringArray{o.SecurityGroupName},
		UserData:       node.UserData,
		Networks: compute.InstanceNetworkArray{
			&compute.InstanceNetworkArgs{Port: port.ID()},
		},
		Region: pulumi.String(o.Region),
	}, opts...)
	if err != nil {
		return nil, err
	}

	fip, err := networking.NewFloatingIp(ctx, resourceName(spec, suffix+"-fip"), &networking.FloatingIpArgs{
		Pool:   pulumi.String(o.FloatingIpPool),
		Region: pulumi.String(o.Region),
	})
	if err != nil {
		return nil, err
	}

	_, err = networking.NewFloatingIpAssociate(ctx, resourceName(spec, suffix+"-fip-assoc"),
		&networking.FloatingIpAssociateArgs{
			FloatingIp: fip.Address,
			PortId:     port.ID(),
			Region:     pulumi.String(o.Region),
		})
	if err != nil {
		return nil, err
	}

	// PrivateIP 는 Port 의 첫 FixedIp — pulumi.AnyOutput → cast 필요.
	privateIp := port.AllFixedIps.Index(pulumi.Int(0)).ApplyT(func(v interface{}) string {
		if v == nil {
			return ""
		}
		if s, ok := v.(string); ok {
			return s
		}
		return ""
	}).(pulumi.StringOutput)

	return &provisioner.InstanceOutput{
		Resource:   instance,
		InstanceID: instance.ID(),
		PrivateIP:  privateIp,
		PublicIP:   fip.Address,
	}, nil
}
