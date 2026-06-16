package model

import (
	"encoding/binary"
	"net"
	"strconv"
	"strings"
)

func ApplyProviderDefaults(spec *ClusterSpec) *ClusterSpec {
	switch CanonicalProviderName(spec.Provider) {
	case "", "aws":
		spec = applyAwsDefaults(spec)
	case "openstack":
		spec = applyOpenStackDefaults(spec)
	case "gcp":
		spec = applyGcpDefaults(spec)
	case "azure":
		spec = applyAzureDefaults(spec)
	case "alibaba":
		spec = applyAlibabaDefaults(spec)
	case "proxmox":
		spec = applyProxmoxDefaults(spec)
	case "oci":
		spec = applyOciDefaults(spec)
	case "digitalocean":
		spec = applyDigitalOceanDefaults(spec)
	default:
		return spec
	}
	spec.Provider = CanonicalProviderName(spec.Provider)
	spec.SubnetCidrs = ensureDefaultSubnets(spec.VpcCidr, spec.SubnetCidrs, requiredSubnetCount(spec.Provider))
	// MasterCount cross-cutting default — 모든 provider 공통. 0 (unset) 이면 single master.
	// 짝수 (2, 4) 는 etcd quorum 의 split-brain 위험이라 odd 로 강제 (3, 5).
	if spec.MasterCount <= 0 {
		spec.MasterCount = 1
	} else if spec.MasterCount%2 == 0 {
		spec.MasterCount = spec.MasterCount + 1
	}
	// root 디스크 cross-cutting default — 모든 provider 공통 50GB (k8s 노드 최소 권장).
	// 0/음수(unset)면 50 으로 정규화. 기본 ~8GB AMI/이미지로는 컨테이너 런타임 + 로그만으로
	// kubelet ephemeral-storage 임계 초과 → NodeHasDiskPressure / eviction.
	if spec.RootDiskSizeGb <= 0 {
		spec.RootDiskSizeGb = 50
	}
	return spec
}

func CanonicalProviderName(provider string) string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "", "aws":
		return "aws"
	case "gcp", "google", "googlecloud":
		return "gcp"
	case "azure", "msazure":
		return "azure"
	case "alibaba", "alicloud", "aliyun":
		return "alibaba"
	case "openstack", "open-stack":
		return "openstack"
	case "proxmox", "proxmoxve", "pve":
		return "proxmox"
	case "oci", "oracle", "oraclecloud", "oraclecloudinfrastructure":
		return "oci"
	case "digitalocean", "digital-ocean", "do":
		return "digitalocean"
	default:
		return strings.ToLower(strings.TrimSpace(provider))
	}
}

func ResolvedOsImage(spec *ClusterSpec) string {
	switch CanonicalProviderName(spec.Provider) {
	case "openstack":
		return spec.OpenstackImageName
	case "gcp":
		return "ubuntu-2404-lts"
	case "azure":
		return "Canonical Ubuntu 24.04 LTS"
	case "alibaba":
		return "Ubuntu 24.04"
	case "proxmox", "oci", "digitalocean":
		return "Ubuntu 24.04"
	default:
		return "ubuntu-24.04"
	}
}

func applyAwsDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "aws"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-demo"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.42.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "t3.large"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "t3.large"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	if spec.Database.Name == "" {
		spec.Database.Name = "anycloud"
	}
	if spec.Database.Username == "" {
		spec.Database.Username = "anycloud"
	}
	if spec.Database.InstanceClass == "" {
		spec.Database.InstanceClass = "db.t4g.micro"
	}
	if spec.Database.AllocatedStorageGb == 0 {
		spec.Database.AllocatedStorageGb = 20
	}
	return spec
}

func applyOpenStackDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "openstack"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-openstack"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.90.0.0/24"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	if spec.OpenstackImageName == "" {
		spec.OpenstackImageName = "ubuntu-24.04"
	}
	if spec.OpenstackFlavorName == "" {
		spec.OpenstackFlavorName = "m1.large"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = spec.OpenstackFlavorName
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = spec.OpenstackFlavorName
	}
	return spec
}

func applyGcpDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "gcp"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-gcp"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.52.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "e2-standard-2"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "e2-standard-2"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	return spec
}

func applyAzureDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "azure"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-azure"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.62.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "Standard_D4s_v5"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "Standard_D4s_v5"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	if spec.AzureResourceGroup == "" {
		spec.AzureResourceGroup = spec.Name + "-rg"
	}
	return spec
}

func applyAlibabaDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "alibaba"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-alibaba"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.72.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "ecs.g6.large"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "ecs.g6.large"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	return spec
}

func applyProxmoxDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "proxmox"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-proxmox"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.84.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "proxmox-standard-2x4"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "proxmox-standard-2x4"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	return spec
}

func applyOciDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "oci"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-oci"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.86.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "ubuntu"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "VM.Standard.E4.Flex"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "VM.Standard.E4.Flex"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	return spec
}

func applyDigitalOceanDefaults(spec *ClusterSpec) *ClusterSpec {
	if spec.Provider == "" {
		spec.Provider = "digitalocean"
	}
	if spec.Name == "" {
		spec.Name = "anycloud-digitalocean"
	}
	if spec.Environment == "" {
		spec.Environment = "dev"
	}
	if spec.VpcCidr == "" {
		spec.VpcCidr = "10.88.0.0/16"
	}
	if spec.SSHUser == "" {
		spec.SSHUser = "root"
	}
	if spec.MasterInstanceType == "" {
		spec.MasterInstanceType = "s-2vcpu-4gb"
	}
	if spec.WorkerInstanceType == "" {
		spec.WorkerInstanceType = "s-2vcpu-4gb"
	}
	if spec.WorkerCount == 0 {
		spec.WorkerCount = 2
	}
	if spec.KubernetesVersion == "" {
		spec.KubernetesVersion = "1.31"
	}
	if spec.PodCidr == "" {
		spec.PodCidr = "192.168.0.0/16"
	}
	if spec.ServiceCidr == "" {
		spec.ServiceCidr = "10.96.0.0/12"
	}
	if tok, err := EnsureJoinToken(spec.JoinToken); err == nil {
		spec.JoinToken = tok
	}
	return spec
}

func requiredSubnetCount(provider string) int {
	switch CanonicalProviderName(provider) {
	case "aws":
		return 2
	default:
		return 1
	}
}

func ensureDefaultSubnets(vpcCidr string, existing []string, required int) []string {
	if required <= 0 {
		required = 1
	}
	subnets := append([]string{}, existing...)
	if len(subnets) >= required {
		return subnets
	}

	generated := generateSubnets(vpcCidr, required)
	if len(subnets) == 0 {
		return generated
	}

	for _, cidr := range generated {
		if len(subnets) >= required {
			break
		}
		if !containsString(subnets, cidr) {
			subnets = append(subnets, cidr)
		}
	}
	return subnets
}

func generateSubnets(vpcCidr string, count int) []string {
	ip, network, err := net.ParseCIDR(vpcCidr)
	if err != nil || ip == nil || network == nil {
		return nil
	}

	baseIP := ip.To4()
	maskSize, bits := network.Mask.Size()
	if baseIP == nil || bits != 32 {
		return nil
	}

	subnetMaskSize := maskSize
	if subnetMaskSize < 24 {
		subnetMaskSize = 24
	}
	if subnetMaskSize > 30 {
		subnetMaskSize = maskSize
	}

	step := uint32(1) << uint32(32-subnetMaskSize)
	base := binary.BigEndian.Uint32(baseIP.Mask(network.Mask))
	subnets := make([]string, 0, count)
	for i := 0; i < count; i++ {
		cidrIP := make(net.IP, 4)
		binary.BigEndian.PutUint32(cidrIP, base+(uint32(i)*step))
		subnets = append(subnets, cidrIP.String()+"/"+itoa(subnetMaskSize))
	}
	return subnets
}

func containsString(items []string, target string) bool {
	for _, item := range items {
		if item == target {
			return true
		}
	}
	return false
}

func itoa(value int) string {
	return strconv.Itoa(value)
}
