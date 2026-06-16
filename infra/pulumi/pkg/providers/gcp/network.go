// AWS sample 의 패턴을 GCP SDK 로 옮긴 것. 동등한 책임: VPC + 단일 regional subnet + 외부/내부
// firewall 2 종. SubnetIDs 는 단일 entry (GCP regional subnet 1개로 충분 — 모든 zone 의 instance 가
// 동일 subnet 사용).
package gcp

import (
	"anycloud/infra/pulumi/pkg/model"
	"anycloud/infra/pulumi/pkg/provisioner"
	"github.com/pulumi/pulumi-gcp/sdk/v8/go/gcp/compute"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

// GcpNetwork — GCP VPC/subnet/firewall 생성.
//
// Provision 후 본 struct 의 Network/Subnetwork/NetworkTag 가 채워져 GcpInstance 가 참조.
type GcpNetwork struct {
	Network    *compute.Network
	Subnetwork *compute.Subnetwork
	// NetworkTag — Firewall TargetTags 와 Instance.Tags 매칭용. AWS 의 SG ID 와 동일 의미.
	NetworkTag string
}

// Provision — interface.NetworkProvisioner 구현.
func (n *GcpNetwork) Provision(ctx *pulumi.Context, spec *model.ClusterSpec) (*provisioner.NetworkOutput, error) {
	network, err := compute.NewNetwork(ctx, resourceName(spec, "vpc"), &compute.NetworkArgs{
		Project:               pulumi.String(spec.GcpProject),
		AutoCreateSubnetworks: pulumi.Bool(false),
		RoutingMode:           pulumi.String("REGIONAL"),
	})
	if err != nil {
		return nil, err
	}

	subnet, err := compute.NewSubnetwork(ctx, resourceName(spec, "subnet"), &compute.SubnetworkArgs{
		Project:               pulumi.String(spec.GcpProject),
		Region:                pulumi.String(spec.Region),
		IpCidrRange:           pulumi.String(spec.SubnetCidrs[0]),
		Network:               network.ID(),
		PrivateIpGoogleAccess: pulumi.Bool(true),
	})
	if err != nil {
		return nil, err
	}

	networkTag := sanitizeName(spec.Name) + "-k8s"

	// External firewall — SSH / kube-apiserver / NodePort + VXLAN/Flannel UDP + ICMP.
	_, err = compute.NewFirewall(ctx, resourceName(spec, "fw"), &compute.FirewallArgs{
		Project: pulumi.String(spec.GcpProject),
		Network: network.Name,
		Allows: compute.FirewallAllowArray{
			compute.FirewallAllowArgs{
				Protocol: pulumi.String("tcp"),
				Ports: pulumi.StringArray{
					pulumi.String("22"),
					pulumi.String("6443"),
					pulumi.String("30000-32767"),
				},
			},
			compute.FirewallAllowArgs{
				Protocol: pulumi.String("udp"),
				Ports: pulumi.StringArray{
					pulumi.String("8472"),
					pulumi.String("4789"),
				},
			},
			compute.FirewallAllowArgs{Protocol: pulumi.String("icmp")},
		},
		SourceRanges: pulumi.StringArray{pulumi.String("0.0.0.0/0")},
		TargetTags:   pulumi.StringArray{pulumi.String(networkTag)},
	})
	if err != nil {
		return nil, err
	}

	// Internal firewall — VPC CIDR 내 모든 트래픽 (pod-to-pod / etcd / kubelet).
	_, err = compute.NewFirewall(ctx, resourceName(spec, "fw-internal"), &compute.FirewallArgs{
		Project: pulumi.String(spec.GcpProject),
		Network: network.Name,
		Allows: compute.FirewallAllowArray{
			compute.FirewallAllowArgs{Protocol: pulumi.String("all")},
		},
		SourceRanges: pulumi.StringArray{pulumi.String(spec.VpcCidr)},
		TargetTags:   pulumi.StringArray{pulumi.String(networkTag)},
	})
	if err != nil {
		return nil, err
	}

	n.Network = network
	n.Subnetwork = subnet
	n.NetworkTag = networkTag

	// GCP 는 SG 개념이 없으므로 SecurityGroup 은 빈 IDOutput. caller (GcpInstance) 는 NetworkTag 로
	// firewall scope 를 매칭 — AWS 와 다른 패턴이지만 NetworkOutput contract 는 충실.
	return &provisioner.NetworkOutput{
		VpcID:         network.ID(),
		SubnetIDs:     []pulumi.IDOutput{subnet.ID()},
		SecurityGroup: pulumi.IDOutput{}, // GCP: no SG concept — 빈 값으로 표시.
	}, nil
}
