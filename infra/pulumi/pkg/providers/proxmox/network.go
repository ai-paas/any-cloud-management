// Proxmox 는 단일 host hypervisor — 별도 VPC/SG 자원이 없다. 본 NetworkProvisioner 는 실제 자원
// 생성 없이 IP 레이아웃만 계산해 ProxmoxNetwork struct 에 보관 → ProxmoxInstance 가 NodeSpec 의
// role/index 로 IP 를 lookup.
package proxmox

import (
	"encoding/binary"
	"fmt"
	"net"

	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// ProxmoxNetwork — VLAN/bridge 는 spec 에서 받아오므로 자원 생성 없음. IP 레이아웃만 계산.
type ProxmoxNetwork struct {
	GatewayIP       string
	NetworkMaskBits int
	MasterIPs       []string
	WorkerIPs       []string
}

func (n *ProxmoxNetwork) Provision(_ *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	masterIPs, workerIPs, gw, mask, err := deriveNetworkLayout(
		spec.SubnetCidrs, spec.MasterCount, spec.WorkerCount)
	if err != nil {
		return nil, err
	}
	n.GatewayIP = gw
	n.NetworkMaskBits = mask
	n.MasterIPs = masterIPs
	n.WorkerIPs = workerIPs

	// Proxmox 는 NetworkOutput contract 의 실 자원이 없음 — 빈 IDOutput 반환.
	return &provisioner.NetworkOutput{
		VpcID:         pulumi.IDOutput{},
		SubnetIDs:     nil,
		SecurityGroup: pulumi.IDOutput{},
	}, nil
}

// deriveNetworkLayout — subnet CIDR 의 첫 IP 가 .0 (network), .1=gateway, .10..=master, .20..=worker.
// 본 PoC 규칙은 단순함 — 큰 cluster (>10 master) 는 .10..(10+M-1) 와 .20..(20+W-1) 가 겹칠 수 있다.
// 그 시점에 layout 알고리즘 재설계 필요 (별 sprint).
func deriveNetworkLayout(subnetCidrs []string, masterCount int, workerCount int) (
	[]string, []string, string, int, error) {
	if len(subnetCidrs) == 0 {
		return nil, nil, "", 0, fmt.Errorf("at least one proxmox subnet CIDR is required")
	}
	if masterCount < 1 {
		masterCount = 1
	}

	ip, network, err := net.ParseCIDR(subnetCidrs[0])
	if err != nil {
		return nil, nil, "", 0, fmt.Errorf("invalid proxmox subnet CIDR %q: %w", subnetCidrs[0], err)
	}

	baseIP := ip.To4()
	if baseIP == nil {
		return nil, nil, "", 0, fmt.Errorf("only IPv4 subnet CIDRs are supported for proxmox")
	}

	maskBits, totalBits := network.Mask.Size()
	if totalBits != 32 {
		return nil, nil, "", 0, fmt.Errorf("only IPv4 subnet masks are supported for proxmox")
	}

	gatewayIP := incrementIPv4(baseIP.Mask(network.Mask), 1)
	masterIPs := make([]string, 0, masterCount)
	for i := 0; i < masterCount; i++ {
		masterIPs = append(masterIPs, incrementIPv4(baseIP.Mask(network.Mask), 10+i))
	}
	workerIPs := make([]string, 0, workerCount)
	for i := 0; i < workerCount; i++ {
		workerIPs = append(workerIPs, incrementIPv4(baseIP.Mask(network.Mask), 20+i))
	}
	return masterIPs, workerIPs, gatewayIP, maskBits, nil
}

func incrementIPv4(base net.IP, offset int) string {
	baseUint := binary.BigEndian.Uint32(base.To4())
	next := make(net.IP, 4)
	binary.BigEndian.PutUint32(next, baseUint+uint32(offset))
	return next.String()
}
