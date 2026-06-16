package alibaba

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AlibabaModule — interface.ProviderModule 구현.
type AlibabaModule struct {
	network  *AlibabaNetwork
	instance *AlibabaInstance
}

func NewModule() *AlibabaModule {
	return &AlibabaModule{
		network:  &AlibabaNetwork{},
		instance: &AlibabaInstance{},
	}
}

func (m *AlibabaModule) Name() string                                { return "alibaba" }
func (m *AlibabaModule) Network() provisioner.NetworkProvisioner     { return m.network }
func (m *AlibabaModule) Instance() provisioner.InstanceProvisioner   { return m.instance }
func (m *AlibabaModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network + image lookup + keypair + RAM role 후 caller 호출.
func (m *AlibabaModule) SetInstanceContext(
	defaultImageID string,
	keypairName pulumi.StringOutput,
	ramRoleName pulumi.StringOutput,
) {
	m.instance.DefaultImageID = defaultImageID
	m.instance.KeypairName = keypairName
	m.instance.RamRoleName = ramRoleName
	m.instance.VSwitchID = m.network.VSwitch.ID()
	m.instance.SecurityGroupID = m.network.SecurityGroup.ID()
}
