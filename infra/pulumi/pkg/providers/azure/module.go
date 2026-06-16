package azure

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// AzureModule — interface.ProviderModule 구현.
type AzureModule struct {
	network  *AzureNetwork
	instance *AzureInstance
}

// NewModule — Network().Provision → SetInstanceContext → Instance().Provision loop 순서.
func NewModule() *AzureModule {
	return &AzureModule{
		network:  &AzureNetwork{},
		instance: &AzureInstance{DefaultImage: defaultImage()},
	}
}

func (m *AzureModule) Name() string { return "azure" }

func (m *AzureModule) Network() provisioner.NetworkProvisioner { return m.network }

func (m *AzureModule) Instance() provisioner.InstanceProvisioner { return m.instance }

// Security — STAGE 4. 현재는 NSG rule 이 network.go 안에 인라인.
func (m *AzureModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 후 caller 가 SSH key 를 만든 뒤 호출.
// Location / RG / SG / Subnet 핸들은 network.go 결과를 그대로 옮긴다.
func (m *AzureModule) SetInstanceContext(
	location, sshUser string,
	sshPublicKey pulumi.StringOutput,
) {
	m.instance.Location = location
	m.instance.SshUser = sshUser
	m.instance.SshPublicKey = sshPublicKey
	m.instance.ResourceGroupName = m.network.ResourceGroup.Name
	m.instance.ResourceGroupID = m.network.ResourceGroup.ID()
	m.instance.SecurityGroupID = m.network.SecurityGroup.ID()
	m.instance.SubnetID = m.network.Subnet.ID()
}
