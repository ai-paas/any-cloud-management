package userdata

import (
	"fmt"

	"anycloud/infra/pulumi/pkg/model"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

func Master(spec *model.ClusterSpec) pulumi.StringInput {
	return pulumi.String(renderNodeTemplate(spec, "master", true))
}

func Worker(spec *model.ClusterSpec) pulumi.StringInput {
	return pulumi.String(renderNodeTemplate(spec, "worker", false))
}

func renderNodeTemplate(spec *model.ClusterSpec, role string, includeMasterPackages bool) string {
	additionalPackages := ""
	if includeMasterPackages {
		additionalPackages = " jq openssl"
	}
	return fmt.Sprintf(nodeTemplate,
		additionalPackages,
		spec.KubernetesVersion,
		spec.KubernetesVersion,
		role,
	)
}

const nodeTemplate = `#!/bin/bash
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive

swapoff -a || true
sed -i.bak '/ swap / s/^/#/' /etc/fstab || true

cat <<'EOF' >/etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF
modprobe overlay
modprobe br_netfilter

cat <<'EOF' >/etc/sysctl.d/99-kubernetes-cri.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sysctl --system

apt-get update
apt-get install -y apt-transport-https ca-certificates curl gpg software-properties-common netcat-openbsd conntrack socat ethtool%s

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v%s/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v%s/deb/ /" >/etc/apt/sources.list.d/kubernetes.list

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" >/etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y containerd.io kubelet kubeadm kubectl
apt-mark hold kubelet kubeadm kubectl

mkdir -p /etc/containerd
containerd config default >/etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/g' /etc/containerd/config.toml
systemctl restart containerd
systemctl enable containerd

mkdir -p /opt/anycloud
echo "%s-prepared" >/opt/anycloud/bootstrap-role
`
