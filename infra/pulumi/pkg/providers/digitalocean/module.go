package digitalocean

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// DoModule — interface.ProviderModule 구현.
type DoModule struct {
	network  *DoNetwork
	instance *DoInstance
}

func NewModule() *DoModule {
	return &DoModule{
		network:  &DoNetwork{},
		instance: &DoInstance{},
	}
}

func (m *DoModule) Name() string                                  { return "digitalocean" }
func (m *DoModule) Network() provisioner.NetworkProvisioner       { return m.network }
func (m *DoModule) Instance() provisioner.InstanceProvisioner     { return m.instance }
func (m *DoModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 + SSH key 후 호출.
func (m *DoModule) SetInstanceContext(region, environment string, sshKeyID pulumi.IDOutput) {
	m.instance.Region = region
	m.instance.Environment = environment
	m.instance.SshKeyID = sshKeyID
	m.instance.VpcID = m.network.Vpc.ID()
}
