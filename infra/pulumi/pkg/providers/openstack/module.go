package openstack

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OpenstackModule — interface.ProviderModule 구현.
type OpenstackModule struct {
	network  *OpenstackNetwork
	instance *OpenstackInstance
}

func NewModule() *OpenstackModule {
	return &OpenstackModule{
		network:  &OpenstackNetwork{},
		instance: &OpenstackInstance{},
	}
}

func (m *OpenstackModule) Name() string                                  { return "openstack" }
func (m *OpenstackModule) Network() provisioner.NetworkProvisioner       { return m.network }
func (m *OpenstackModule) Instance() provisioner.InstanceProvisioner     { return m.instance }
func (m *OpenstackModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 + keypair 후 caller 호출.
func (m *OpenstackModule) SetInstanceContext(
	region, floatingIpPool, defaultImageName, defaultFlavorName string,
	keypairName pulumi.StringOutput,
) {
	m.instance.Region = region
	m.instance.FloatingIpPool = floatingIpPool
	m.instance.DefaultImageName = defaultImageName
	m.instance.DefaultFlavorName = defaultFlavorName
	m.instance.KeypairName = keypairName
	m.instance.NetworkID = m.network.Network.ID()
	m.instance.SubnetID = m.network.Subnet.ID()
	m.instance.SecurityGroupID = m.network.SecurityGroup.ID()
	m.instance.SecurityGroupName = m.network.SecurityGroup.Name
}
