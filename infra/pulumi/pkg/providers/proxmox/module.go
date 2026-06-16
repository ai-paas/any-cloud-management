package proxmox

import (
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// ProxmoxModule — interface.ProviderModule 구현.
type ProxmoxModule struct {
	network  *ProxmoxNetwork
	instance *ProxmoxInstance
}

func NewModule() *ProxmoxModule {
	return &ProxmoxModule{
		network:  &ProxmoxNetwork{},
		instance: &ProxmoxInstance{},
	}
}

func (m *ProxmoxModule) Name() string                                { return "proxmox" }
func (m *ProxmoxModule) Network() provisioner.NetworkProvisioner     { return m.network }
func (m *ProxmoxModule) Instance() provisioner.InstanceProvisioner   { return m.instance }
func (m *ProxmoxModule) Security() provisioner.SecurityPolicyTranslator { return nil }

// SetInstanceContext — Network 단계 + SSH key + cloud-init snippet 2개 (master/worker) 후 호출.
func (m *ProxmoxModule) SetInstanceContext(
	nodeName string,
	templateVmId int,
	datastoreId, networkBridge, sshUser string,
	sshPublicKey pulumi.StringOutput,
	masterUserDataFileID, workerUserDataFileID pulumi.StringOutput,
) {
	m.instance.NodeName = nodeName
	m.instance.TemplateVmId = templateVmId
	m.instance.DatastoreId = datastoreId
	m.instance.NetworkBridge = networkBridge
	m.instance.SshUser = sshUser
	m.instance.SshPublicKey = sshPublicKey
	m.instance.GatewayIP = m.network.GatewayIP
	m.instance.NetworkMaskBits = m.network.NetworkMaskBits
	m.instance.MasterIPs = m.network.MasterIPs
	m.instance.WorkerIPs = m.network.WorkerIPs
	m.instance.MasterUserDataFileID = masterUserDataFileID
	m.instance.WorkerUserDataFileID = workerUserDataFileID
}
