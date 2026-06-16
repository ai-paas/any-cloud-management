// 본 파일은 provisioner.NetworkProvisioner 의 AWS 구현 sample. 기존 aws.go 의 VPC/subnet/IGW/RT/SG
// 코드를 이쪽으로 추출 — Provisioner.Provision 은 본 타입을 사용해 동일 결과를 만든다 (회귀 없음).
//
// 본 sample 의 가치:
//  - 다른 7개 provider 마이그레이션 시 본 파일을 template 로 복사 → cloud-specific SDK 만 교체.
//  - NetworkProvisioner.Provision 의 반환 NetworkOutput 은 cloud-agnostic — 후속 instance 단계가
//    AWS/GCP/Azure 어디든 동일한 contract 로 subnet/SG 핸들을 받을 수 있다.
package aws

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	awsmeta "github.com/pulumi/pulumi-aws/sdk/v6/go/aws"
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AwsNetwork — AWS VPC/subnet/IGW/RT/SG 정규화 생성.
//
// Provision 호출 후 본 struct 의 필드 (Vpc/Subnets/SecurityGroup) 에 native Pulumi resource 가
// 채워진다. 후속 단계가 AWS-only resource (RDS 등) 를 만들 때 본 핸들을 그대로 사용.
type AwsNetwork struct {
	Vpc           *ec2.Vpc
	Subnets       []*ec2.Subnet
	SecurityGroup *ec2.SecurityGroup
}

// Provision — interface.NetworkProvisioner 구현.
func (n *AwsNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	zones, err := awsmeta.GetAvailabilityZones(ctx, nil, nil)
	if err != nil {
		return nil, err
	}
	if len(zones.Names) < 2 {
		return nil, fmt.Errorf("at least two availability zones are required to create the sample VPC layout")
	}

	vpc, err := ec2.NewVpc(ctx, resourceName(spec, "vpc"), &ec2.VpcArgs{
		CidrBlock:          pulumi.String(spec.VpcCidr),
		EnableDnsHostnames: pulumi.Bool(true),
		EnableDnsSupport:   pulumi.Bool(true),
		Tags:               tags(spec, "vpc"),
	})
	if err != nil {
		return nil, err
	}

	internetGateway, err := ec2.NewInternetGateway(ctx, resourceName(spec, "igw"), &ec2.InternetGatewayArgs{
		VpcId: vpc.ID(),
		Tags:  tags(spec, "igw"),
	})
	if err != nil {
		return nil, err
	}

	routeTable, err := ec2.NewRouteTable(ctx, resourceName(spec, "rt"), &ec2.RouteTableArgs{
		VpcId: vpc.ID(),
		Routes: ec2.RouteTableRouteArray{
			ec2.RouteTableRouteArgs{
				CidrBlock: pulumi.String("0.0.0.0/0"),
				GatewayId: internetGateway.ID(),
			},
		},
		Tags: tags(spec, "rt"),
	})
	if err != nil {
		return nil, err
	}

	subnets := make([]*ec2.Subnet, 0, len(spec.SubnetCidrs))
	subnetIds := make([]pulumi.IDOutput, 0, len(spec.SubnetCidrs))
	for i, cidr := range spec.SubnetCidrs {
		subnet, subnetErr := ec2.NewSubnet(ctx, resourceName(spec, fmt.Sprintf("subnet-%d", i+1)), &ec2.SubnetArgs{
			VpcId:               vpc.ID(),
			CidrBlock:           pulumi.String(cidr),
			AvailabilityZone:    pulumi.String(zones.Names[i%len(zones.Names)]),
			MapPublicIpOnLaunch: pulumi.Bool(true),
			Tags:                tags(spec, fmt.Sprintf("subnet-%d", i+1)),
		})
		if subnetErr != nil {
			return nil, subnetErr
		}
		_, assocErr := ec2.NewRouteTableAssociation(ctx,
			resourceName(spec, fmt.Sprintf("rta-%d", i+1)),
			&ec2.RouteTableAssociationArgs{
				RouteTableId: routeTable.ID(),
				SubnetId:     subnet.ID(),
			})
		if assocErr != nil {
			return nil, assocErr
		}
		subnets = append(subnets, subnet)
		subnetIds = append(subnetIds, subnet.ID())
	}

	nodeSecurityGroup, err := ec2.NewSecurityGroup(ctx, resourceName(spec, "nodes-sg"), &ec2.SecurityGroupArgs{
		VpcId:       vpc.ID(),
		Description: pulumi.String("anycloud kubernetes node security group"),
		Ingress: ec2.SecurityGroupIngressArray{
			ec2.SecurityGroupIngressArgs{
				Protocol:    pulumi.String("tcp"),
				FromPort:    pulumi.Int(22),
				ToPort:      pulumi.Int(22),
				CidrBlocks:  pulumi.StringArray{pulumi.String("0.0.0.0/0")},
				Description: pulumi.String("ssh"),
			},
			ec2.SecurityGroupIngressArgs{
				Protocol:    pulumi.String("tcp"),
				FromPort:    pulumi.Int(6443),
				ToPort:      pulumi.Int(6443),
				CidrBlocks:  pulumi.StringArray{pulumi.String("0.0.0.0/0")},
				Description: pulumi.String("kubernetes api"),
			},
			ec2.SecurityGroupIngressArgs{
				Protocol:    pulumi.String("-1"),
				FromPort:    pulumi.Int(0),
				ToPort:      pulumi.Int(0),
				Self:        pulumi.Bool(true),
				Description: pulumi.String("allow all node to node traffic"),
			},
			ec2.SecurityGroupIngressArgs{
				Protocol:    pulumi.String("tcp"),
				FromPort:    pulumi.Int(30000),
				ToPort:      pulumi.Int(32767),
				CidrBlocks:  pulumi.StringArray{pulumi.String("0.0.0.0/0")},
				Description: pulumi.String("nodeport range"),
			},
		},
		Egress: ec2.SecurityGroupEgressArray{
			ec2.SecurityGroupEgressArgs{
				Protocol:    pulumi.String("-1"),
				FromPort:    pulumi.Int(0),
				ToPort:      pulumi.Int(0),
				CidrBlocks:  pulumi.StringArray{pulumi.String("0.0.0.0/0")},
				Description: pulumi.String("all outbound"),
			},
		},
		Tags: tags(spec, "nodes-sg"),
	})
	if err != nil {
		return nil, err
	}

	n.Vpc = vpc
	n.Subnets = subnets
	n.SecurityGroup = nodeSecurityGroup

	return &provisioner.NetworkOutput{
		VpcID:         vpc.ID(),
		SubnetIDs:     subnetIds,
		SecurityGroup: nodeSecurityGroup.ID(),
	}, nil
}
