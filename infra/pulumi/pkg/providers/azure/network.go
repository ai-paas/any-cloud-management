// AWS / GCP 와의 큰 차이: Azure 는 ResourceGroup 개념이 있어 모든 자원의 컨테이너로 작동. RG 는
// network.go 단계에서 생성 — instance 단계가 위치/RG 이름/SubnetID 만 받아 동작 가능.
package azure

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-azure/sdk/v6/go/azure/core"
	"github.com/pulumi/pulumi-azure/sdk/v6/go/azure/network"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AzureNetwork — Azure RG + VNet + Subnet + NSG (3 rules) 생성.
type AzureNetwork struct {
	ResourceGroup  *core.ResourceGroup
	VirtualNetwork *network.VirtualNetwork
	Subnet         *network.Subnet
	SecurityGroup  *network.NetworkSecurityGroup
}

// Provision — interface.NetworkProvisioner 구현.
func (n *AzureNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	resourceGroup, err := core.NewResourceGroup(ctx, resourceName(spec, "rg"), &core.ResourceGroupArgs{
		Name:     pulumi.String(spec.AzureResourceGroup),
		Location: pulumi.String(spec.Region),
	})
	if err != nil {
		return nil, err
	}

	virtualNetwork, err := network.NewVirtualNetwork(ctx, resourceName(spec, "vnet"), &network.VirtualNetworkArgs{
		Name:              pulumi.String(resourceName(spec, "vnet")),
		Location:          resourceGroup.Location,
		ResourceGroupName: resourceGroup.Name,
		AddressSpaces:     pulumi.StringArray{pulumi.String(spec.VpcCidr)},
	})
	if err != nil {
		return nil, err
	}

	subnet, err := network.NewSubnet(ctx, resourceName(spec, "subnet"), &network.SubnetArgs{
		Name:               pulumi.String(resourceName(spec, "subnet")),
		ResourceGroupName:  resourceGroup.Name,
		VirtualNetworkName: virtualNetwork.Name,
		AddressPrefixes:    pulumi.StringArray{pulumi.String(spec.SubnetCidrs[0])},
	})
	if err != nil {
		return nil, err
	}

	securityGroup, err := network.NewNetworkSecurityGroup(ctx, resourceName(spec, "nsg"), &network.NetworkSecurityGroupArgs{
		Name:              pulumi.String(resourceName(spec, "nsg")),
		Location:          resourceGroup.Location,
		ResourceGroupName: resourceGroup.Name,
	})
	if err != nil {
		return nil, err
	}

	// 3 default rules — ssh / kube-apiserver / NodePort range. Priority 100/110/120.
	for idx, rule := range []struct {
		name      string
		priority  int
		protocol  string
		portRange string
	}{
		{"ssh", 100, "Tcp", "22"},
		{"k8s-api", 110, "Tcp", "6443"},
		{"nodeport", 120, "Tcp", "30000-32767"},
	} {
		_, ruleErr := network.NewNetworkSecurityRule(ctx,
			resourceName(spec, fmt.Sprintf("nsg-rule-%d", idx+1)),
			&network.NetworkSecurityRuleArgs{
				Name:                     pulumi.String(rule.name),
				ResourceGroupName:        resourceGroup.Name,
				NetworkSecurityGroupName: securityGroup.Name,
				Access:                   pulumi.String("Allow"),
				Direction:                pulumi.String("Inbound"),
				Priority:                 pulumi.Int(rule.priority),
				Protocol:                 pulumi.String(rule.protocol),
				SourcePortRange:          pulumi.String("*"),
				DestinationPortRange:     pulumi.String(rule.portRange),
				SourceAddressPrefix:      pulumi.String("*"),
				DestinationAddressPrefix: pulumi.String("*"),
			})
		if ruleErr != nil {
			return nil, ruleErr
		}
	}

	n.ResourceGroup = resourceGroup
	n.VirtualNetwork = virtualNetwork
	n.Subnet = subnet
	n.SecurityGroup = securityGroup

	return &provisioner.NetworkOutput{
		VpcID:         virtualNetwork.ID(),
		SubnetIDs:     []pulumi.IDOutput{subnet.ID()},
		SecurityGroup: securityGroup.ID(),
	}, nil
}
