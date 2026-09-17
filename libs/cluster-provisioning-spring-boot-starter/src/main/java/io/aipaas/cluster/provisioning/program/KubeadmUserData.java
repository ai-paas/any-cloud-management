package io.aipaas.cluster.provisioning.program;

/** kubeadm 기반 k8s 노드 bootstrap 의 cloud-init 스크립트 생성. */
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

    private static final String NODE_TEMPLATE =
            """
            #!/bin/bash
            set -euxo pipefail

            export DEBIAN_FRONTEND=noninteractive
            # 클라우드 이미지는 첫 부팅에 unattended-upgrades 로 apt 를 돌린다. 락을 기다리지
            # 않으면 apt-get 이 즉시 죽고 set -e 가 스크립트 전체를 중단시킨다.
            #
            # DPkg::Lock::Timeout 은 dpkg 락만 덮는다. apt-get update 가 잡는
            # /var/lib/apt/lists/lock 은 덮지 않아 옵션만으로는 부족하다.
            APT_LOCK='-o DPkg::Lock::Timeout=600'
            apt_wait() {
              for _ in $(seq 1 180); do
                fuser /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend \
                  >/dev/null 2>&1 || return 0
                sleep 5
              done
              echo 'apt lock still held after 15m' >&2
              return 1
            }

            swapoff -a || true
            sed -i.bak '/ swap / s/^/#/' /etc/fstab || true

            # 일부 클라우드 이미지(OCI Ubuntu)는 SSH 만 허용하고 나머지를 REJECT 하는 iptables
            # 규칙을 담고 온다. kube-apiserver 는 6443 을 열어도 노드 안에서 거부되어 워커가 영영
            # join 하지 못한다. 경계는 CSP 보안 그룹이 정하므로 노드 안에서 또 막지 않는다.
            # 규칙이 없는 이미지에서는 -C 가 실패해 아무 일도 하지 않는다.
            for chain in INPUT FORWARD; do
              while iptables -C "$chain" -j REJECT --reject-with icmp-host-prohibited 2>/dev/null; do
                iptables -D "$chain" -j REJECT --reject-with icmp-host-prohibited
              done
            done
            netfilter-persistent save 2>/dev/null || true

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

            apt_wait && apt-get $APT_LOCK update
            apt_wait && apt-get $APT_LOCK install -y apt-transport-https ca-certificates curl gpg software-properties-common netcat-openbsd conntrack socat ethtool%s

            install -m 0755 -d /etc/apt/keyrings
            curl -fsSL https://pkgs.k8s.io/core:/stable:/v%s/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
            echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v%s/deb/ /" >/etc/apt/sources.list.d/kubernetes.list

            curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
            echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" >/etc/apt/sources.list.d/docker.list

            apt_wait && apt-get $APT_LOCK update
            apt_wait && apt-get $APT_LOCK install -y containerd.io kubelet kubeadm kubectl
            apt-mark hold kubelet kubeadm kubectl

            mkdir -p /etc/containerd
            containerd config default >/etc/containerd/config.toml
            sed -i 's/SystemdCgroup = false/SystemdCgroup = true/g' /etc/containerd/config.toml
            systemctl restart containerd
            systemctl enable containerd

            # NVIDIA GPU operator 의 driver 컨테이너는 nouveau 와 공존할 수 없다. 부팅 시점에
            # 처리해야 재부팅 비용이 없다. GPU 없는 노드에서는 로드되지 않아 무해하다.
            cat <<'EOF' >/etc/modprobe.d/blacklist-nouveau.conf
            blacklist nouveau
            options nouveau modeset=0
            EOF
            update-initramfs -u
            if lsmod | grep -q '^nouveau '; then modprobe -r nouveau; fi

            mkdir -p /opt/anycloud
            echo "%s-prepared" >/opt/anycloud/bootstrap-role
            """;
}
