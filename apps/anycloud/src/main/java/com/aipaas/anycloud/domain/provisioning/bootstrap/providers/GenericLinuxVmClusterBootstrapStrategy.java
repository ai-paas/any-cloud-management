package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import org.springframework.stereotype.Component;

@Component
public class GenericLinuxVmClusterBootstrapStrategy implements VmClusterBootstrapStrategy {

    private static final String CONFIG_JOIN_TOKEN = "anycloud-k8s:joinToken";

    @Override
    public boolean supports(String provider) {
        return provider == null || provider.isBlank();
    }

    @Override
    public String waitForPreparationCommand() {
        return "sudo cloud-init status --wait || cloud-init status --wait || true; "
                + "until systemctl is-active --quiet containerd; do sleep 5; done; "
                + "until systemctl is-enabled --quiet kubelet; do sleep 5; done";
    }

    @Override
    public String initializeMasterCommand(VmClusterInternalRequestSnapshot snapshot) {
        String podCidr = firstNonBlank(snapshot.getPodCidr(), "192.168.0.0/16");
        String serviceCidr = firstNonBlank(snapshot.getServiceCidr(), "10.96.0.0/12");
        String joinToken = requiredJoinToken(snapshot);
        // HA mode (masterCount >= 2) — kubeadm init 에 --control-plane-endpoint + --upload-certs.
        // lead master 의 IP 자체를 endpoint 로 사용 (LB/VIP 미적용 — 본 PoC 한계).
        boolean multiMaster = masterCount(snapshot) >= 2;
        StringBuilder cmd = new StringBuilder(512);
        cmd.append("if [ ! -f /etc/kubernetes/admin.conf ]; then ");
        cmd.append("LOCAL_IP=$(hostname -I | awk '{print $1}'); ");
        cmd.append("sudo kubeadm init ");
        cmd.append("--apiserver-advertise-address=\"${LOCAL_IP}\" ");
        if (multiMaster) {
            // LB/VIP 미사용 — lead master IP 가 그대로 endpoint. 진짜 HA 는 LB 필요.
            cmd.append("--control-plane-endpoint=\"${LOCAL_IP}:6443\" ");
            cmd.append("--upload-certs ");
        }
        cmd.append("--pod-network-cidr=").append(shellWord(podCidr)).append(' ');
        cmd.append("--service-cidr=").append(shellWord(serviceCidr)).append(' ');
        cmd.append("--token=").append(shellWord(joinToken)).append(' ');
        cmd.append("--ignore-preflight-errors=NumCPU,Mem; ");
        cmd.append("fi; ");
        cmd.append("sudo mkdir -p /root/.kube; ");
        cmd.append("sudo cp /etc/kubernetes/admin.conf /root/.kube/config; ");
        cmd.append("sudo chmod 600 /root/.kube/config");
        return cmd.toString();
    }

    @Override
    public String buildControlPlaneJoinCommand(
            VmClusterInternalRequestSnapshot snapshot,
            String leadMasterPrivateIp,
            String caHash,
            String certificateKey) {
        String joinToken = requiredJoinToken(snapshot);
        return "until nc -z " + shellWord(leadMasterPrivateIp) + " 6443; do sleep 10; done; "
                + "if [ ! -f /etc/kubernetes/kubelet.conf ]; then "
                + "sudo kubeadm join "
                + shellWord(leadMasterPrivateIp) + ":6443 " + "--token="
                + shellWord(joinToken) + " " + "--discovery-token-ca-cert-hash sha256:"
                + shellWord(caHash) + " " + "--control-plane "
                + "--certificate-key "
                + shellWord(certificateKey) + "; " + "sudo mkdir -p /root/.kube; "
                + "sudo cp /etc/kubernetes/admin.conf /root/.kube/config; "
                + "sudo chmod 600 /root/.kube/config; "
                + "fi";
    }

    private int masterCount(VmClusterInternalRequestSnapshot snapshot) {
        String raw = config(snapshot, "anycloud-k8s:masterCount");
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public String resolveCaHashCommand() {
        return "sudo openssl x509 -pubkey -in /etc/kubernetes/pki/ca.crt | "
                + "sudo openssl rsa -pubin -outform der 2>/dev/null | "
                + "sha256sum | awk '{print $1}'";
    }

    @Override
    public String buildWorkerJoinCommand(
            VmClusterInternalRequestSnapshot snapshot, String masterPrivateIp, String caHash) {
        String joinToken = requiredJoinToken(snapshot);
        return "until nc -z " + shellWord(masterPrivateIp) + " 6443; do sleep 10; done; "
                + "if [ ! -f /etc/kubernetes/kubelet.conf ]; then "
                + "sudo kubeadm join "
                + shellWord(masterPrivateIp) + ":6443 " + "--token="
                + shellWord(joinToken) + " " + "--discovery-token-ca-cert-hash sha256:"
                + shellWord(caHash) + "; " + "fi";
    }

    @Override
    public String waitForClusterReadyCommand() {
        return "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl wait --for=condition=Ready node --all --timeout=10m";
    }

    @Override
    public String buildAddonInstallCommand(VmClusterInternalRequestSnapshot snapshot) {
        StringBuilder commands = new StringBuilder();
        append(commands, cniInstallCommand());

        if (Boolean.TRUE.equals(snapshot.getEnableIngress())) {
            append(commands, ingressInstallCommand(snapshot));
            append(
                    commands,
                    "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl wait --namespace ingress-nginx "
                            + "--for=condition=available deployment/ingress-nginx-controller --timeout=10m || true");
        }

        if (Boolean.TRUE.equals(snapshot.getEnableGpuOperator())) {
            append(
                    commands,
                    "command -v helm >/dev/null 2>&1 || curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash");
            append(
                    commands,
                    "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get namespace gpu-operator >/dev/null 2>&1 || "
                            + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl create namespace gpu-operator");
            append(commands, gpuPreparationCommand(snapshot));
            append(commands, "helm repo add nvidia https://helm.ngc.nvidia.com/nvidia >/dev/null 2>&1 || true");
            append(commands, "helm repo update >/dev/null 2>&1");
            append(
                    commands,
                    "helm upgrade --install gpu-operator nvidia/gpu-operator --namespace gpu-operator "
                            + "--set driver.enabled=true --set toolkit.enabled=true --set dcgmExporter.serviceMonitor.enabled=true");
            append(
                    commands,
                    "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl wait --namespace gpu-operator "
                            + "--for=condition=available deployment/gpu-operator --timeout=15m || true");
        }

        return commands.toString();
    }

    protected String cniInstallCommand() {
        return "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get daemonset calico-node -n kube-system >/dev/null 2>&1 || "
                + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.28.2/manifests/calico.yaml";
    }

    protected String ingressInstallCommand(VmClusterInternalRequestSnapshot snapshot) {
        return "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get deployment ingress-nginx-controller -n ingress-nginx >/dev/null 2>&1 || "
                + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl apply -f "
                + shellWord(ingressManifestUrl(snapshot));
    }

    protected String ingressManifestUrl(VmClusterInternalRequestSnapshot snapshot) {
        return "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.1/deploy/static/provider/cloud/deploy.yaml";
    }

    protected String gpuPreparationCommand(VmClusterInternalRequestSnapshot snapshot) {
        if (isUbuntuLike(snapshot)) {
            return "sudo apt-get update && sudo apt-get install -y ubuntu-drivers-common && sudo ubuntu-drivers install --gpgpu || true";
        }
        return "echo 'Skipping automatic GPU driver install for non-Ubuntu image' >/tmp/anycloud-gpu-driver-skip.log";
    }

    protected boolean isUbuntuLike(VmClusterInternalRequestSnapshot snapshot) {
        String osImage = snapshot.getOsImage();
        if (osImage == null) {
            return false;
        }
        String normalized = osImage.toLowerCase();
        return normalized.contains("ubuntu") || normalized.contains("jammy") || normalized.contains("noble");
    }

    /**
     * Snapshot 의 joinToken — 없으면 fail-fast. 과거엔 공유 하드코딩 token
     * ({@code abcdef.0123456789abcdef}) 으로 silent fallback 했으나, 모든 cluster 가 같은 token 을
     * 공유하면 node 탈취 시 타 cluster join 이 가능해지는 보안 결함. 정상 생성 경로는 backend 가
     * 항상 random token 을 snapshot 에 영속화하므로 여기 도달 시 누락 = snapshot 손상.
     */
    protected String requiredJoinToken(VmClusterInternalRequestSnapshot snapshot) {
        String joinToken = config(snapshot, CONFIG_JOIN_TOKEN);
        if (joinToken == null || joinToken.isBlank()) {
            throw new IllegalStateException("joinToken missing from request snapshot (" + CONFIG_JOIN_TOKEN
                    + ") — refusing to fall back to a shared token");
        }
        return joinToken;
    }

    protected String config(VmClusterInternalRequestSnapshot snapshot, String key) {
        if (snapshot.getProviderConfig() == null) {
            return null;
        }
        return snapshot.getProviderConfig().get(key);
    }

    protected String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    protected String shellWord(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void append(StringBuilder builder, String command) {
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(command);
    }
}
