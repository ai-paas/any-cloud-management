// AwsInstance 는 단일 EC2 instance 생성 책임을 가진다. AWS-only setup (AMI 조회, KeyPair, IAM
// instance profile) 은 caller (Provisioner.Provision) 가 미리 만들어 본 struct 의 필드로 주입.
//
// 본 분리의 핵심: provider-agnostic NodeSpec 만 받아도 instance 한 대를 생성할 수 있다는 사실을
// 증명. Java 측 VmClusterSpec 에 새 옵션 (예: gpuType, customTags) 이 추가되면 NodeSpec 에 필드
// 추가 → 본 AwsInstance 와 다른 7개 provider 의 같은 위치만 손대면 끝.
package aws

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/iam"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AwsInstance — interface.InstanceProvisioner 구현.
//
// AMI / KeyPair / InstanceProfile 은 ProviderModule level 에서 한 번 만들어 모든 instance 가 공유
// (8개 master/worker 마다 별도 IAM role 만들 이유 없음).
type AwsInstance struct {
	Ami             string
	KeyPair         *ec2.KeyPair
	InstanceProfile *iam.InstanceProfile
}

// Provision — 단일 NodeSpec 을 EC2 Instance 한 대로 변환.
//
// spot 처리: NodeSpec.UseSpot=true 면 InstanceMarketOptions 추가 (master 는 helper 단계에서
// 강제 false 이므로 본 함수는 그대로 forwarding).
func (a *AwsInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	net *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if a.KeyPair == nil || a.InstanceProfile == nil || a.Ami == "" {
		return nil, fmt.Errorf("AwsInstance: keypair/instanceProfile/ami must be set before Provision")
	}
	if node.SubnetIndex < 0 || node.SubnetIndex >= len(net.SubnetIDs) {
		return nil, fmt.Errorf("AwsInstance: subnetIndex %d out of range (have %d subnets)",
			node.SubnetIndex, len(net.SubnetIDs))
	}

	tagSuffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)
	args := &ec2.InstanceArgs{
		Ami:                      pulumi.String(a.Ami),
		InstanceType:             pulumi.String(node.InstanceType),
		SubnetId:                 net.SubnetIDs[node.SubnetIndex].ToStringOutput(),
		VpcSecurityGroupIds:      pulumi.StringArray{net.SecurityGroup.ToStringOutput()},
		IamInstanceProfile:       a.InstanceProfile.Name,
		KeyName:                  a.KeyPair.KeyName,
		AssociatePublicIpAddress: pulumi.Bool(true),
		UserData:                 node.UserData,
		Tags:                     tags(spec, tagSuffix),
	}
	if node.UseSpot {
		args.InstanceMarketOptions = &ec2.InstanceInstanceMarketOptionsArgs{
			MarketType: pulumi.String("spot"),
			SpotOptions: &ec2.InstanceInstanceMarketOptionsSpotOptionsArgs{
				SpotInstanceType:             pulumi.String("one-time"),
				InstanceInterruptionBehavior: pulumi.String("terminate"),
			},
		}
	}
	// root EBS 볼륨 크기 명시 — 미지정 시 AMI 기본(~8GB)으로 kubelet ephemeral-storage eviction
	// (NodeHasDiskPressure) 발생. model.defaults 가 0 이하를 50 으로 정규화하므로 보통 >0.
	if node.RootDiskSizeGb > 0 {
		args.RootBlockDevice = &ec2.InstanceRootBlockDeviceArgs{
			VolumeSize:          pulumi.Int(node.RootDiskSizeGb),
			VolumeType:          pulumi.String("gp3"),
			DeleteOnTermination: pulumi.Bool(true),
		}
	}

	instance, err := ec2.NewInstance(ctx, resourceName(spec, tagSuffix), args, opts...)
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
