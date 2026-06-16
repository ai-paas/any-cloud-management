// Proxmox 의 VM 은 template clone + cloud-init (nocloud) 형태. Storage.File (cloud-config snippet)
// 은 orchestrator 가 role 별로 2개 (master / worker) 생성해 모든 같은 role 의 VM 이 공유.
//
// Spot 매핑: 없음 (Proxmox 는 하드웨어 hypervisor).
// OS image: template VM ID (spec.ProxmoxTemplateVmId) — node.OsImage 는 무시 (spec 사전 검증).
package proxmox

import (
	"fmt"
	"strconv"
	"strings"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/muhlba91/pulumi-proxmoxve/sdk/v7/go/proxmoxve/vm"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// ProxmoxInstance — interface.InstanceProvisioner 구현.
type ProxmoxInstance struct {
	NodeName             string
	TemplateVmId         int
	DatastoreId          string
	NetworkBridge        string
	SshPublicKey         pulumi.StringOutput
	SshUser              string
	GatewayIP            string
	NetworkMaskBits      int
	MasterIPs            []string
	WorkerIPs            []string
	MasterUserDataFileID pulumi.StringOutput
	WorkerUserDataFileID pulumi.StringOutput
}

func (p *ProxmoxInstance) Provision(
	ctx *pulumi.Context,
	spec *model.ClusterSpec,
	_ *provisioner.NetworkOutput,
	node provisioner.NodeSpec,
	opts ...pulumi.ResourceOption,
) (*provisioner.InstanceOutput, error) {
	if p.NodeName == "" || p.TemplateVmId == 0 || p.DatastoreId == "" || p.NetworkBridge == "" {
		return nil, fmt.Errorf("ProxmoxInstance: nodeName/templateVmId/datastoreId/networkBridge must be set")
	}

	var (
		ip             string
		userDataFileID pulumi.StringOutput
	)
	if node.Role == provisioner.RoleMaster {
		if node.Index >= len(p.MasterIPs) {
			return nil, fmt.Errorf("masterIndex %d out of range (have %d IPs)", node.Index, len(p.MasterIPs))
		}
		ip = p.MasterIPs[node.Index]
		userDataFileID = p.MasterUserDataFileID
	} else {
		if node.Index >= len(p.WorkerIPs) {
			return nil, fmt.Errorf("workerIndex %d out of range (have %d IPs)", node.Index, len(p.WorkerIPs))
		}
		ip = p.WorkerIPs[node.Index]
		userDataFileID = p.WorkerUserDataFileID
	}

	cores, memoryMb := parseProxmoxSpec(node.InstanceType)
	suffix := fmt.Sprintf("%s-%d", node.Role, node.Index+1)

	vmObj, err := vm.NewVirtualMachine(ctx, resourceName(spec, suffix), &vm.VirtualMachineArgs{
		NodeName: pulumi.String(p.NodeName),
		Name:     pulumi.String(resourceName(spec, suffix)),
		OnBoot:   pulumi.Bool(true),
		Clone: &vm.VirtualMachineCloneArgs{
			NodeName: pulumi.String(p.NodeName),
			VmId:     pulumi.Int(p.TemplateVmId),
			Full:     pulumi.Bool(true),
		},
		OperatingSystem: &vm.VirtualMachineOperatingSystemArgs{
			Type: pulumi.String("l26"),
		},
		Cpu: &vm.VirtualMachineCpuArgs{
			Cores:   pulumi.Int(cores),
			Sockets: pulumi.Int(1),
		},
		Memory: &vm.VirtualMachineMemoryArgs{
			Dedicated: pulumi.Int(memoryMb),
		},
		Agent: &vm.VirtualMachineAgentArgs{
			Enabled: pulumi.Bool(false),
			Trim:    pulumi.Bool(true),
			Type:    pulumi.String("virtio"),
		},
		Disks: vm.VirtualMachineDiskArray{
			&vm.VirtualMachineDiskArgs{
				Interface:   pulumi.String("scsi0"),
				DatastoreId: pulumi.String(p.DatastoreId),
				// 디스크 크기(GB). model.defaults 가 0 이하를 50 으로 정규화. NodeHasDiskPressure 방지.
				Size:       pulumi.Int(node.RootDiskSizeGb),
				FileFormat: pulumi.String("qcow2"),
			},
		},
		NetworkDevices: vm.VirtualMachineNetworkDeviceArray{
			&vm.VirtualMachineNetworkDeviceArgs{
				Bridge: pulumi.String(p.NetworkBridge),
				Model:  pulumi.String("virtio"),
			},
		},
		Initialization: &vm.VirtualMachineInitializationArgs{
			Type: pulumi.String("nocloud"),
			UserAccount: &vm.VirtualMachineInitializationUserAccountArgs{
				Username: pulumi.String(p.SshUser),
				Keys:     pulumi.StringArray{p.SshPublicKey},
			},
			IpConfigs: vm.VirtualMachineInitializationIpConfigArray{
				&vm.VirtualMachineInitializationIpConfigArgs{
					Ipv4: &vm.VirtualMachineInitializationIpConfigIpv4Args{
						Address: pulumi.String(fmt.Sprintf("%s/%d", ip, p.NetworkMaskBits)),
						Gateway: pulumi.String(p.GatewayIP),
					},
				},
			},
			UserDataFileId: userDataFileID,
		},
		Description: pulumi.StringPtr(fmt.Sprintf("Anycloud Kubernetes %s", suffix)),
	}, opts...)
	if err != nil {
		return nil, err
	}

	return &provisioner.InstanceOutput{
		Resource:   vmObj,
		InstanceID: vmObj.ID(),
		PrivateIP:  pulumi.String(ip).ToStringOutput(),
		PublicIP:   pulumi.String("").ToStringOutput(),
	}, nil
}

// parseProxmoxSpec — InstanceType 문자열 ("proxmox-standard-4x8" / "*-4x8" 일반 형식) → (cores, MB).
// fallback default = 2 cores / 4096 MB.
func parseProxmoxSpec(specName string) (int, int) {
	switch strings.ToLower(specName) {
	case "proxmox-standard-4x8":
		return 4, 8192
	case "proxmox-standard-8x16":
		return 8, 16384
	case "proxmox-gpu-8x32":
		return 8, 32768
	}
	parts := strings.Split(strings.ToLower(specName), "-")
	if len(parts) > 0 {
		last := parts[len(parts)-1]
		if strings.Contains(last, "x") {
			cm := strings.SplitN(last, "x", 2)
			if len(cm) == 2 {
				if cores, err := strconv.Atoi(cm[0]); err == nil {
					if memoryGb, err := strconv.Atoi(cm[1]); err == nil {
						return cores, memoryGb * 1024
					}
				}
			}
		}
	}
	return 2, 4096
}
