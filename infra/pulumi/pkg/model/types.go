package model

type DatabaseSpec struct {
	Enabled            bool
	Name               string
	Username           string
	Password           string
	InstanceClass      string
	AllocatedStorageGb int
	PubliclyAccessible bool
}

type ClusterSpec struct {
	Provider             string
	Name                 string
	Environment          string
	Region               string
	GcpProject           string
	AzureResourceGroup   string
	OciCompartmentId     string
	ProxmoxNodeName      string
	ProxmoxTemplateVmId  int
	ProxmoxDatastoreId   string
	ProxmoxNetworkBridge string
	VpcCidr              string
	SubnetCidrs          []string
	SSHUser              string
	MasterInstanceType   string
	WorkerInstanceType   string
	// MasterCount — control-plane 노드 수 (HA). 1 이면 single master (legacy 동작), 3 이상
	// 권장 (etcd quorum). 짝수는 split-brain 위험으로 비권장.
	// 본 PoC 구현 한계: 첫 master 의 IP 를 control-plane endpoint 로 사용 — VIP/LB 미적용
	// 이므로 master-1 장애 시 새 join 불가 (기존 컴포넌트는 정상). 진짜 HA 는 kube-vip /
	// keepalived 추가 필요 (별 sprint).
	MasterCount                int
	WorkerCount                int
	KubernetesVersion          string
	PodCidr                    string
	ServiceCidr                string
	JoinToken                  string
	EnableIngress              bool
	EnableGpuOperator          bool
	OpenstackImageName         string
	OpenstackFlavorName        string
	OpenstackExternalNetworkId string
	OpenstackFloatingIpPool    string
	Database                   DatabaseSpec

	// Spot/preemptible instance 사용.
	// AWS: spot request, Azure: Spot priority VM, GCP: preemptible, Alibaba: SpotAsPriceGo.
	// 그 외 provider — no-op (지원 X 또는 의미 없음).
	// 비용 30-70% 절감 — capacity 회수 시 instance 종료 가능. dev/CI workload 권장.
	UseSpot bool

	// OS image override (provider 별 형식).
	// 비어 있으면 provider 별 default (보통 Ubuntu 22.04 LTS, model/defaults.go 참조).
	// AWS: AMI ID, Azure: Image URN, GCP: image family, Alibaba: ImageId 등.
	OsImage string

	// 노드 root(boot) 디스크 크기 (GB). 0 이하면 model/defaults 가 provider 별 기본(50GB) 적용.
	// 너무 작으면 kubelet ephemeral-storage eviction (NodeHasDiskPressure) — 기본 ~8GB AMI 로는
	// 컨테이너 런타임 + 로그만으로 임계 초과. config key: rootDiskSizeGb.
	RootDiskSizeGb int
}
