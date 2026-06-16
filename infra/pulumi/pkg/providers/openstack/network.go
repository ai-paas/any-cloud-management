// OpenStack 의 network 모델은 Network + Subnet + Router + RouterInterface (external network 연결) +
// SecurityGroup 5-rules. AWS/GCP/Azure 와 비교해 Router 가 독립 객체라는 차이.
package openstack

import (
	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-openstack/sdk/v5/go/openstack/networking"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OpenstackNetwork — Network + Subnet + Router + 5-rule SG.
type OpenstackNetwork struct {
	Network       *networking.Network
	Subnet        *networking.Subnet
	Router        *networking.Router
	SecurityGroup *networking.SecGroup
}

func (n *OpenstackNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	network, err := networking.NewNetwork(ctx, resourceName(spec, "network"), &networking.NetworkArgs{
		Name:         pulumi.String(resourceName(spec, "network")),
		AdminStateUp: pulumi.Bool(true),
		Region:       pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	subnet, err := networking.NewSubnet(ctx, resourceName(spec, "subnet"), &networking.SubnetArgs{
		Name:      pulumi.String(resourceName(spec, "subnet")),
		NetworkId: network.ID(),
		Cidr:      pulumi.String(spec.SubnetCidrs[0]),
		IpVersion: pulumi.Int(4),
		Region:    pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	router, err := networking.NewRouter(ctx, resourceName(spec, "router"), &networking.RouterArgs{
		Name:              pulumi.String(resourceName(spec, "router")),
		AdminStateUp:      pulumi.Bool(true),
		ExternalNetworkId: pulumi.String(spec.OpenstackExternalNetworkId),
		Region:            pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	_, err = networking.NewRouterInterface(ctx, resourceName(spec, "router-if"), &networking.RouterInterfaceArgs{
		RouterId: router.ID(),
		SubnetId: subnet.ID(),
		Region:   pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	securityGroup, err := networking.NewSecGroup(ctx, resourceName(spec, "sg"), &networking.SecGroupArgs{
		Name:               pulumi.String(resourceName(spec, "sg")),
		Description:        pulumi.String("anycloud kubernetes security group"),
		DeleteDefaultRules: pulumi.Bool(false),
		Region:             pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	for _, rule := range []struct {
		name     string
		protocol string
		min, max int
		cidr     string
	}{
		{"ssh", "tcp", 22, 22, "0.0.0.0/0"},
		{"k8s-api", "tcp", 6443, 6443, "0.0.0.0/0"},
		{"nodeport", "tcp", 30000, 32767, "0.0.0.0/0"},
		{"intra-tcp", "tcp", 1, 65535, spec.VpcCidr},
		{"intra-udp", "udp", 1, 65535, spec.VpcCidr},
	} {
		_, ruleErr := networking.NewSecGroupRule(ctx, resourceName(spec, rule.name), &networking.SecGroupRuleArgs{
			Direction:       pulumi.String("ingress"),
			Ethertype:       pulumi.String("IPv4"),
			Protocol:        pulumi.String(rule.protocol),
			PortRangeMin:    pulumi.Int(rule.min),
			PortRangeMax:    pulumi.Int(rule.max),
			RemoteIpPrefix:  pulumi.String(rule.cidr),
			SecurityGroupId: securityGroup.ID(),
			Region:          pulumi.String(spec.Region),
		})
		if ruleErr != nil {
			return nil, ruleErr
		}
	}

	n.Network = network
	n.Subnet = subnet
	n.Router = router
	n.SecurityGroup = securityGroup

	return &provisioner.NetworkOutput{
		VpcID:         network.ID(),
		SubnetIDs:     []pulumi.IDOutput{subnet.ID()},
		SecurityGroup: securityGroup.ID(),
	}, nil
}
