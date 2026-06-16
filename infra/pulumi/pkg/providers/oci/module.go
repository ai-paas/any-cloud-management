package oci

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// OciModule — interface.ProviderModule 구현.
type OciModule struct {
	network  *OciNetwork
	instance *OciInstance
}

func NewModule() *OciModule {
	return &OciModule{
		network:  &OciNetwork{},
		instance: &OciInstance{},
	}
}

func (m *OciModule) Name() string                                { return "oci" }
func (m *OciModule) Network() provisioner.NetworkProvisioner     { return m.network }
func (m *OciModule) Instance() provisioner.InstanceProvisioner   { return m.instance }
func (m *OciModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 후 caller 가 SSH key + AD 결정 후 호출.
func (m *OciModule) SetInstanceContext(compartmentId, availabilityDomain string,
		sshPublicKey pulumi.StringOutput) {
	m.instance.CompartmentId = compartmentId
	m.instance.AvailabilityDomain = availabilityDomain
	m.instance.SshPublicKey = sshPublicKey
	m.instance.SubnetID = m.network.Subnet.ID()
}
