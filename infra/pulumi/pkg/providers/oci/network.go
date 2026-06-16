// OCI 의 VCN/Subnet 구조는 AWS 와 유사하지만 IGW/RouteTable/SecurityList 가 VCN 의 default
// resource 로 묶여 있다는 차이. CCM 권한을 위한 Dynamic Group + Policy 는 orchestrator (oci.go) 가
// 담당 — network 책임 분리 원칙 유지.
package oci

import (
	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-oci/sdk/v3/go/oci/core"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OciNetwork — OCI VCN + IGW + default route/SL + Subnet 생성.
type OciNetwork struct {
	Vcn           *core.Vcn
	Subnet        *core.Subnet
	SecurityList  *core.DefaultSecurityList
	InternetGw    *core.InternetGateway
}

// Provision — interface.NetworkProvisioner 구현.
func (n *OciNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	vcn, err := core.NewVcn(ctx, resourceName(spec, "vcn"), &core.VcnArgs{
		CidrBlock:     pulumi.String(spec.VpcCidr),
		CompartmentId: pulumi.String(spec.OciCompartmentId),
		DisplayName:   pulumi.String(resourceName(spec, "vcn")),
		DnsLabel:      pulumi.String("anycloud"),
	})
	if err != nil {
		return nil, err
	}

	internetGateway, err := core.NewInternetGateway(ctx, resourceName(spec, "igw"), &core.InternetGatewayArgs{
		CompartmentId: pulumi.String(spec.OciCompartmentId),
		VcnId:         vcn.ID(),
		DisplayName:   pulumi.String(resourceName(spec, "igw")),
		Enabled:       pulumi.Bool(true),
	})
	if err != nil {
		return nil, err
	}

	routeTable, err := core.NewDefaultRouteTable(ctx, resourceName(spec, "rt"), &core.DefaultRouteTableArgs{
		ManageDefaultResourceId: vcn.DefaultRouteTableId,
		RouteRules: core.DefaultRouteTableRouteRuleArray{
			&core.DefaultRouteTableRouteRuleArgs{
				NetworkEntityId: internetGateway.ID(),
				Destination:     pulumi.String("0.0.0.0/0"),
				DestinationType: pulumi.String("CIDR_BLOCK"),
			},
		},
	})
	if err != nil {
		return nil, err
	}

	securityList, err := core.NewDefaultSecurityList(ctx, resourceName(spec, "sl"), &core.DefaultSecurityListArgs{
		ManageDefaultResourceId: vcn.DefaultSecurityListId,
		IngressSecurityRules: core.DefaultSecurityListIngressSecurityRuleArray{
			&core.DefaultSecurityListIngressSecurityRuleArgs{
				Protocol: pulumi.String("6"),
				Source:   pulumi.String("0.0.0.0/0"),
				TcpOptions: &core.DefaultSecurityListIngressSecurityRuleTcpOptionsArgs{
					Min: pulumi.Int(22), Max: pulumi.Int(22),
				},
			},
			&core.DefaultSecurityListIngressSecurityRuleArgs{
				Protocol: pulumi.String("6"),
				Source:   pulumi.String("0.0.0.0/0"),
				TcpOptions: &core.DefaultSecurityListIngressSecurityRuleTcpOptionsArgs{
					Min: pulumi.Int(6443), Max: pulumi.Int(6443),
				},
			},
			&core.DefaultSecurityListIngressSecurityRuleArgs{
				Protocol: pulumi.String("6"),
				Source:   pulumi.String("0.0.0.0/0"),
				TcpOptions: &core.DefaultSecurityListIngressSecurityRuleTcpOptionsArgs{
					Min: pulumi.Int(30000), Max: pulumi.Int(32767),
				},
			},
			&core.DefaultSecurityListIngressSecurityRuleArgs{
				Protocol: pulumi.String("all"),
				Source:   pulumi.String(spec.VpcCidr),
			},
		},
		EgressSecurityRules: core.DefaultSecurityListEgressSecurityRuleArray{
			&core.DefaultSecurityListEgressSecurityRuleArgs{
				Protocol:    pulumi.String("all"),
				Destination: pulumi.String("0.0.0.0/0"),
			},
		},
	})
	if err != nil {
		return nil, err
	}

	subnet, err := core.NewSubnet(ctx, resourceName(spec, "subnet"), &core.SubnetArgs{
		CidrBlock:              pulumi.String(spec.SubnetCidrs[0]),
		CompartmentId:          pulumi.String(spec.OciCompartmentId),
		VcnId:                  vcn.ID(),
		DisplayName:            pulumi.String(resourceName(spec, "subnet")),
		RouteTableId:           routeTable.ID(),
		SecurityListIds:        pulumi.StringArray{securityList.ID().ToStringOutput()},
		ProhibitPublicIpOnVnic: pulumi.Bool(false),
		DnsLabel:               pulumi.String("k8s"),
	})
	if err != nil {
		return nil, err
	}

	n.Vcn = vcn
	n.Subnet = subnet
	n.SecurityList = securityList
	n.InternetGw = internetGateway

	return &provisioner.NetworkOutput{
		VpcID:         vcn.ID(),
		SubnetIDs:     []pulumi.IDOutput{subnet.ID()},
		SecurityGroup: securityList.ID(),
	}, nil
}
