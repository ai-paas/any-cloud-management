// Alibaba 의 네트워킹은 VPC + VSwitch (subnet) + Security Group + rules. AWS 와 거의 동일한 모델.
package alibaba

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud/ecs"
	"github.com/pulumi/pulumi-alicloud/sdk/v3/go/alicloud/vpc"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AlibabaNetwork — VPC + VSwitch + SG + 4 rules.
type AlibabaNetwork struct {
	Vpc           *vpc.Network
	VSwitch       *vpc.Switch
	SecurityGroup *ecs.SecurityGroup
}

func (n *AlibabaNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	vpcNetwork, err := vpc.NewNetwork(ctx, resourceName(spec, "vpc"), &vpc.NetworkArgs{
		VpcName:   pulumi.String(resourceName(spec, "vpc")),
		CidrBlock: pulumi.String(spec.VpcCidr),
	})
	if err != nil {
		return nil, err
	}

	// 첫 사용 가능 zone — caller 가 zone 을 사전 추출해 주입할 수도 있지만, 본 sample 에서는
	// network.go 가 자체적으로 lookup (다른 provider 와 일관).
	zone, err := pickZone(ctx)
	if err != nil {
		return nil, err
	}

	vswitch, err := vpc.NewSwitch(ctx, resourceName(spec, "vsw"), &vpc.SwitchArgs{
		VpcId:       vpcNetwork.ID(),
		ZoneId:      pulumi.String(zone),
		CidrBlock:   pulumi.String(spec.SubnetCidrs[0]),
		VswitchName: pulumi.String(resourceName(spec, "vsw")),
	})
	if err != nil {
		return nil, err
	}

	securityGroup, err := ecs.NewSecurityGroup(ctx, resourceName(spec, "sg"), &ecs.SecurityGroupArgs{
		Name:        pulumi.String(resourceName(spec, "sg")),
		VpcId:       vpcNetwork.ID(),
		Description: pulumi.String("anycloud kubernetes security group"),
	})
	if err != nil {
		return nil, err
	}

	for idx, portRange := range []string{"22/22", "6443/6443", "30000/32767"} {
		_, ruleErr := ecs.NewSecurityGroupRule(ctx,
			resourceName(spec, fmt.Sprintf("sg-rule-%d", idx+1)),
			&ecs.SecurityGroupRuleArgs{
				Type:            pulumi.String("ingress"),
				IpProtocol:      pulumi.String("tcp"),
				NicType:         pulumi.String("intranet"),
				Policy:          pulumi.String("accept"),
				PortRange:       pulumi.String(portRange),
				Priority:        pulumi.Int(1),
				SecurityGroupId: securityGroup.ID(),
				CidrIp:          pulumi.String("0.0.0.0/0"),
			})
		if ruleErr != nil {
			return nil, ruleErr
		}
	}

	// Intra-SG: 같은 SG 안의 모든 instance 끼리 무제한 통신 — etcd / kubelet / pod CNI 용.
	_, err = ecs.NewSecurityGroupRule(ctx, resourceName(spec, "sg-rule-self"), &ecs.SecurityGroupRuleArgs{
		Type:                  pulumi.String("ingress"),
		IpProtocol:            pulumi.String("all"),
		NicType:               pulumi.String("intranet"),
		Policy:                pulumi.String("accept"),
		PortRange:             pulumi.String("-1/-1"),
		Priority:              pulumi.Int(1),
		SecurityGroupId:       securityGroup.ID(),
		SourceSecurityGroupId: securityGroup.ID().ToStringOutput(),
	})
	if err != nil {
		return nil, err
	}

	n.Vpc = vpcNetwork
	n.VSwitch = vswitch
	n.SecurityGroup = securityGroup

	return &provisioner.NetworkOutput{
		VpcID:         vpcNetwork.ID(),
		SubnetIDs:     []pulumi.IDOutput{vswitch.ID()},
		SecurityGroup: securityGroup.ID(),
	}, nil
}
