package io.aipaas.cluster.provisioning.program;

import io.aipaas.cluster.provisioning.program.ClusterSpec;

/**
 * kubeadm 기반 k8s 노드 bootstrap 의 cloud-init 스크립트 생성. Go {@code infra/pulumi/pkg/userdata/kubeadm.go}
 * 등가물. Java text block + format() — 16-bit Go fmt 대체.
 *
 * <p>master 와 worker 모두 동일한 OS prepare 단계 (containerd, kubelet, kubeadm 설치) 를 수행하고,
 * master 만 추가로 jq/openssl 패키지 설치 (kubeadm init 시 token/cert 처리 도구). 실제 kubeadm init/join
 * 은 본 user-data 이후 별도 단계 (Ansible 또는 SSH script) — 본 user-data 는 OS 준비만 담당.
 */
public final class KubeadmUserData {

    private KubeadmUserData() {}

    public static String master(ClusterSpec spec) {
        return render(spec, "master", true);
    }

    public static String worker(ClusterSpec spec) {
        return render(spec, "worker", false);
    }

    private static String render(ClusterSpec spec, String role, boolean includeMasterPackages) {
        // master 만 추가로 jq + openssl 설치 — kubeadm init 시 join token / cert hash 처리에 사용.
        String additionalPackages = includeMasterPackages ? " jq openssl" : "";
        String k8sVersion = spec.kubernetesVersion();
        // 두 번 사용 (apt-key 다운로드 URL + apt sources.list deb URL).
        return NODE_TEMPLATE.formatted(additionalPackages, k8sVersion, k8sVersion, role);
    }

    private static final String NODE_TEMPLATE = """
            #!/bin/bash
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
            """;
}
