// Package provisioner — provider 별 코드 8중 복제를 줄이기 위한 공통 추상화.
//
// 현재 상태:
//   - interface 정의 + SecurityPolicy 정규화 + InstanceProvisioner + NodeSpec 도입
//   - NodeSpecsFor helper — ClusterSpec 으로부터 master/worker NodeSpec list 생성 (spot / OS image 매핑)
//   - 기존 provider 코드는 그대로 — 새 interface 와 공존 (backward compat)
//
// 향후: 각 provider 를 본 interface 구현으로 점진 마이그레이션. 한 번에 8개 provider 를 옮기지
// 않음 — 회귀 위험이 너무 크다.
//
// 목표 형태:
//
//	type ProviderModule interface {
//	    Name() string
//	    Network() NetworkProvisioner
//	    Instance() InstanceProvisioner
//	    Security() SecurityPolicyTranslator
//	}
//
// 마이그레이션 순서 (권장):
//  1. AWS (가장 잘 테스트됨, 412 LOC — sample)
//  2. GCP (290 LOC, AWS 다음 잘 알려짐)
//  3. Azure (340 LOC)
//  4. OCI / Alibaba / OpenStack / Proxmox / DigitalOcean (순서 무관)
package provisioner

import (
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"

	"anycloud/infra/pulumi/pkg/model"
)

// NetworkOutput — provider 가 network 생성 결과를 정규화해 반환하는 타입.
type NetworkOutput struct {
	VpcID         pulumi.IDOutput
	SubnetIDs     []pulumi.IDOutput
	SecurityGroup pulumi.IDOutput
}

// NetworkProvisioner — 8개 provider 의 VPC/subnet/security-group 코드 정규화 대상.
type NetworkProvisioner interface {
	Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*NetworkOutput, error)
}

// InstanceRole — master / worker 구분.
type InstanceRole string

const (
	RoleMaster InstanceRole = "master"
	RoleWorker InstanceRole = "worker"
)

// NodeSpec — Phase 단일 instance 의 정규화된 사양.
//
// VmClusterSpec (Java) 의 typed 필드 + config map 이 본 NodeSpec 으로 변환되어 각 provider 의
// InstanceProvisioner 에게 전달됨. provider-specific 매핑 (AMI ID / image URN / 등) 은 각 구현이.
type NodeSpec struct {
	Role         InstanceRole
	Index        int    // 0-based — master-1 → index 0
	InstanceType string // 예: "t3.medium" (AWS), "e2-standard-4" (GCP), "Standard_D2s_v3" (Azure)
	OsImage      string // empty 면 provider default. 형식은 provider 별 (AMI / URN / family).
	UseSpot      bool   // master 는 항상 false 강제 (control-plane stability)
	UserData     pulumi.StringInput
	SubnetIndex  int // network.SubnetIDs[i] 중 i (zone 분산용)
	// RootDiskSizeGb — root(boot) 디스크 크기(GB). 0 이면 provider 기본. model.defaults 가 50 으로
	// 정규화하므로 보통 >0. NodeHasDiskPressure 방지용.
	RootDiskSizeGb int
}

// InstanceOutput — provider 가 instance 생성 결과 정규화 반환.
//
// Resource 는 created 된 Pulumi resource 본체 — caller 가 후속 instance 의 DependsOn 으로 사용한다
// (예: worker 들이 master 의 join 토큰 준비를 기다림). InstanceID/PrivateIP/PublicIP 는 outputs.
type InstanceOutput struct {
	Resource   pulumi.Resource
	InstanceID pulumi.IDOutput
	PrivateIP  pulumi.StringOutput
	PublicIP   pulumi.StringOutput // 외부 IP 없으면 빈 문자열
}

// InstanceProvisioner — instance 단일 생성 인터페이스. caller (provider.Provision) 가 master/worker
// loop 안에서 호출.
//
// 본 interface 도입 이유: NodeSpec 가 provider-agnostic 이므로, Java 측의 VmClusterSpec 옵션
// 추가 (예: spot, custom AMI) 가 자동으로 모든 provider 에 전파됨. 각 provider 는 자기 cloud
// 의 SDK 호출로 NodeSpec 을 번역하기만 하면 됨.
//
// opts 는 Pulumi resource option (DependsOn 등) — provider 가 SDK 호출에 그대로 전달.
type InstanceProvisioner interface {
	Provision(ctx *pulumi.Context, spec *model.ClusterSpec, net *NetworkOutput, node NodeSpec,
		opts ...pulumi.ResourceOption) (*InstanceOutput, error)
}

// SecurityPolicy — provider-agnostic security rule 표현. AWS '-1' / Azure 'Tcp' / GCP 'all'
// 같은 protocol 표현 불일치를 한 곳에 모은다.
type SecurityPolicy struct {
	Ingress []Rule
	Egress  []Rule
}

// Rule — 단일 firewall rule. Protocol 은 "tcp"/"udp"/"icmp"/"all" 만 허용.
type Rule struct {
	Protocol    string   // "tcp" | "udp" | "icmp" | "all"
	FromPort    int      // 0 = any
	ToPort      int      // 0 = any (FromPort 가 0 이고 ToPort 도 0 이면 전체)
	CidrBlocks  []string // "0.0.0.0/0" 등
	Description string
}

// SecurityPolicyTranslator — provider 가 자신만의 firewall 자원 인자로 변환할 때 구현.
type SecurityPolicyTranslator interface {
	Translate(policy SecurityPolicy) (any, error)
}

// ProviderModule — 한 provider 의 3가지 책임을 한 객체로 묶는 최종 목표 interface.
// 마이그레이션 완료된 provider 만 본 interface 구현. 미완 provider 는 기존 Provisioner 그대로.
type ProviderModule interface {
	Name() string
	Network() NetworkProvisioner
	Instance() InstanceProvisioner
	Security() SecurityPolicyTranslator
}

// DefaultK8sClusterPolicy — kubeadm 기반 자체 호스트 cluster 의 일반적인 ingress 규칙.
// 각 provider 는 이 policy 를 받아 자신의 SG/firewall 로 번역한다.
func DefaultK8sClusterPolicy() SecurityPolicy {
	return SecurityPolicy{
		Ingress: []Rule{
			{Protocol: "tcp", FromPort: model.PortSSH, ToPort: model.PortSSH,
				CidrBlocks: []string{"0.0.0.0/0"}, Description: "SSH"},
			{Protocol: "tcp", FromPort: model.PortKubernetesAPIServer, ToPort: model.PortKubernetesAPIServer,
				CidrBlocks: []string{"0.0.0.0/0"}, Description: "kube-apiserver"},
			{Protocol: "tcp", FromPort: model.NodePortRangeMin, ToPort: model.NodePortRangeMax,
				CidrBlocks: []string{"0.0.0.0/0"}, Description: "NodePort range"},
		},
		Egress: []Rule{
			{Protocol: "all", FromPort: 0, ToPort: 0,
				CidrBlocks: []string{"0.0.0.0/0"}, Description: "Egress any"},
		},
	}
}

// NodeSpecsFor — ClusterSpec 으로부터 master/worker 의 NodeSpec list 생성하는 helper.
//
// master 는 항상 useSpot=false (control-plane stability). worker 만 spec.UseSpot 따름.
// instance type / OS image 는 spec 의 ResolvedOsImage / provider default 사용.
//
// 마이그레이션된 provider 만 본 helper 사용. 미완 provider 는 기존 master/worker loop 그대로.
func NodeSpecsFor(spec *model.ClusterSpec) []NodeSpec {
	nodes := make([]NodeSpec, 0, spec.MasterCount+spec.WorkerCount)
	for i := 0; i < spec.MasterCount; i++ {
		nodes = append(nodes, NodeSpec{
			Role:           RoleMaster,
			Index:          i,
			InstanceType:   spec.MasterInstanceType,
			OsImage:        spec.OsImage,
			UseSpot:        false, // master 는 항상 on-demand
			SubnetIndex:    i % maxSubnets(spec),
			RootDiskSizeGb: spec.RootDiskSizeGb,
		})
	}
	for i := 0; i < spec.WorkerCount; i++ {
		nodes = append(nodes, NodeSpec{
			Role:           RoleWorker,
			Index:          i,
			InstanceType:   spec.WorkerInstanceType,
			OsImage:        spec.OsImage,
			UseSpot:        spec.UseSpot,
			SubnetIndex:    i % maxSubnets(spec),
			RootDiskSizeGb: spec.RootDiskSizeGb,
		})
	}
	return nodes
}

func maxSubnets(spec *model.ClusterSpec) int {
	if len(spec.SubnetCidrs) == 0 {
		return 1
	}
	return len(spec.SubnetCidrs)
}
