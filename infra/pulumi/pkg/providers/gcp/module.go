package gcp

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-gcp/sdk/v8/go/gcp/serviceaccount"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// GcpModule — interface.ProviderModule 구현 (Security 는 firewall 인라인이므로 nil).
type GcpModule struct {
	network  *GcpNetwork
	instance *GcpInstance
}

// NewModule — caller 는 Network().Provision 호출 → SetInstanceContext 로 image/SA/SSH key 주입 →
// Instance().Provision loop 의 순서로 사용.
func NewModule() *GcpModule {
	return &GcpModule{
		network:  &GcpNetwork{},
		instance: &GcpInstance{},
	}
}

func (m *GcpModule) Name() string { return "gcp" }

func (m *GcpModule) Network() provisioner.NetworkProvisioner { return m.network }

func (m *GcpModule) Instance() provisioner.InstanceProvisioner { return m.instance }

// Security — STAGE 4. 현재는 firewall 이 network.go 안에 인라인 (default policy 만).
func (m *GcpModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 후 caller 가 image / SA / SSH key 를 만든 뒤 호출.
func (m *GcpModule) SetInstanceContext(
	project, region string,
	zones []string,
	imageSelfLink string,
	sa *serviceaccount.Account,
	sshPublicKey pulumi.StringOutput,
) {
	m.instance.Project = project
	m.instance.Region = region
	m.instance.Zones = zones
	m.instance.ImageSelfLink = imageSelfLink
	m.instance.NetworkTag = m.network.NetworkTag
	m.instance.SubnetworkID = m.network.Subnetwork.ID()
	m.instance.ServiceAccountEmail = sa.Email
	m.instance.SshPublicKey = sshPublicKey
}
