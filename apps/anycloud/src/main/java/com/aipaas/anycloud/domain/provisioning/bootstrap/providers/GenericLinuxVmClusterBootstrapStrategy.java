package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import org.springframework.stereotype.Component;

@Component
public class GenericLinuxVmClusterBootstrapStrategy implements VmClusterBootstrapStrategy {

    private static final String CONFIG_JOIN_TOKEN = "anycloud-k8s:joinToken";

    /**
     * 준비 대기 상한: 5초 간격 60회로 유닛당 5분, 두 유닛 합쳐 최대 10분.
     *
     * <p>RabbitMQ 의 delivery ack 타임아웃(30분)보다 충분히 짧아야 한다. 넘기면 채널이 끊기고
     * 메시지가 재전달되어 같은 노드에 부트스트랩이 중복으로 붙는다. 실제로 그렇게 됐다.
     */
    private static final int PREPARATION_WAIT_ATTEMPTS = 60;

    /** apiserver 대기 상한: 10초 간격 30회로 5분. 준비 대기와 동일하게 ack 타임아웃보다 짧게 설정. */
    private static final int API_SERVER_WAIT_ATTEMPTS = 30;

    private static final int API_SERVER_WAIT_INTERVAL_SEC = 10;

    private static final int PREPARATION_WAIT_INTERVAL_SEC = 5;

    @Override
    public boolean supports(String provider) {
        return provider == null || provider.isBlank();
    }

    @Override
    public String waitForPreparationCommand() {
        // cloud-init 실패 시 containerd 와 kubelet 미기동. 상한 부재 시 SSH 세션이 유지된 채
        // 워크플로가 BOOTSTRAPPING 에 정지하고 로그도 없어 원인 파악 불가. 중단 시 cloud-init
        // 상태를 함께 출력.
        return "sudo cloud-init status --wait || cloud-init status --wait || true; "
                + "wait_unit() { "
                + "  for _ in $(seq 1 " + PREPARATION_WAIT_ATTEMPTS + "); do "
                + "    \"$@\" && return 0; sleep " + PREPARATION_WAIT_INTERVAL_SEC + "; "
                + "  done; "
                + "  echo \"timed out waiting for $*\" >&2; "
                + "  sudo cloud-init status --long >&2 2>/dev/null || true; "
                + "  sudo tail -40 /var/log/cloud-init-output.log >&2 2>/dev/null || true; "
                + "  return 1; "
                + "}; "
                + "wait_unit systemctl is-active --quiet containerd; "
                + "wait_unit systemctl is-enabled --quiet kubelet";
    }

    @Override
    public String initializeMasterCommand(VmClusterInternalRequestSnapshot snapshot, String apiServerPublicIp) {
        String podCidr = firstNonBlank(snapshot.getPodCidr(), DEFAULT_POD_CIDR);
        String serviceCidr = firstNonBlank(snapshot.getServiceCidr(), "10.96.0.0/12");
        String joinToken = requiredJoinToken(snapshot);
        // HA mode (masterCount >= 2). kubeadm init 에 --control-plane-endpoint + --upload-certs.
        // lead master 의 IP 자체를 endpoint 로 사용 (LB/VIP 미적용: 본 PoC 한계).
        boolean multiMaster = masterCount(snapshot) >= 2;
        StringBuilder cmd = new StringBuilder(512);
        cmd.append("if [ ! -f /etc/kubernetes/admin.conf ]; then ");
        cmd.append("LOCAL_IP=$(hostname -I | awk '{print $1}'); ");
        cmd.append("sudo kubeadm init ");
        cmd.append("--apiserver-advertise-address=\"${LOCAL_IP}\" ");
        if (multiMaster) {
            // LB/VIP 미사용: lead master IP 가 그대로 endpoint. 진짜 HA 는 LB 필요.
            cmd.append("--control-plane-endpoint=\"${LOCAL_IP}:6443\" ");
            cmd.append("--upload-certs ");
        }
        if (apiServerPublicIp != null && !apiServerPublicIp.isBlank()) {
            /*
             * 인증서 SAN 에는 advertise 주소만 포함. 수신한 kubeconfig 가 공인 IP 를 가리키면
             * 노드 정상 기동 상태에서도 TLS 이름 검증 실패. 발급 후 수정 불가하므로 여기서 지정.
             */
            cmd.append("--apiserver-cert-extra-sans=")
                    .append(shellWord(apiServerPublicIp))
                    .append(' ');
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
        return waitForApiServer(leadMasterPrivateIp)
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

    /**
     * 마스터 apiserver 기동 대기.
     *
     * <p>상한 부재 시 마스터 기동 실패에서 워커가 무한 대기하고 SSH 세션이 유지돼 워크플로 정지.
     * AMQP delivery ack 타임아웃 초과 시 메시지 재전달로 동일 노드에 부트스트랩 중복 실행.
     */
    private String waitForApiServer(String privateIp) {
        return "for _ in $(seq 1 " + API_SERVER_WAIT_ATTEMPTS + "); do "
                + "  nc -z " + shellWord(privateIp) + " 6443 && break; "
                + "  sleep " + API_SERVER_WAIT_INTERVAL_SEC + "; "
                + "done; "
                + "nc -z " + shellWord(privateIp) + " 6443 || "
                + "{ echo 'timed out waiting for apiserver at " + privateIp + ":6443' >&2; exit 1; }; ";
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
        return waitForApiServer(masterPrivateIp)
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

    /**
     * CNI 만 유지. GPU 와 ingress 는 컴포넌트 소유. 셸 설치 시 실패가 {@code || true} 로 묻히고
     * 재시도 주체 부재.
     */
    @Override
    public String buildAddonInstallCommand(VmClusterInternalRequestSnapshot snapshot) {
        StringBuilder commands = new StringBuilder();
        append(commands, cniInstallCommand(firstNonBlank(snapshot.getPodCidr(), DEFAULT_POD_CIDR)));
        return commands.toString();
    }

    /**
     * Calico stock manifest 는 pool CIDR 이 주석 처리 상태이며 내장 기본값은
     * {@code 192.168.0.0/16}. 온프레미스 망 대부분이 이 대역에 포함돼 파드의 게이트웨이, DNS
     * 접근 차단. kubeadm 에 전달한 {@code --pod-network-cidr} 은 Calico 미참조.
     *
     * <p>주석 해제 후 실제 pod CIDR 주입. upstream 문구 변경 시 치환이 무음으로 실패해 동일
     * 장애가 재현되므로 적용 전 치환 결과 확인, 불일치 시 중단.
     */
    protected String cniInstallCommand(String podCidr) {
        String kubectl = "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl";
        return kubectl + " get daemonset calico-node -n kube-system >/dev/null 2>&1 || { "
                + "set -e; "
                + "CALICO_MANIFEST=$(mktemp); "
                + "curl -fsSL " + CALICO_MANIFEST_URL + " -o \"$CALICO_MANIFEST\"; "
                + "sed -i " + calicoSedArgs(podCidr, usesVxlanEncapsulation()) + " \"$CALICO_MANIFEST\"; "
                + "grep -q '^ *- name: CALICO_IPV4POOL_CIDR' \"$CALICO_MANIFEST\" || "
                + "{ echo 'calico manifest: CALICO_IPV4POOL_CIDR 주석 해제 실패' >&2; exit 1; }; "
                + "grep -q '^ *value: \"" + podCidr + "\"' \"$CALICO_MANIFEST\" || "
                + "{ echo 'calico manifest: pod CIDR 치환 실패' >&2; exit 1; }; "
                + kubectl + " apply -f \"$CALICO_MANIFEST\"; "
                + "rm -f \"$CALICO_MANIFEST\"; }";
    }

    /** upstream 문구에 의존하는 유일한 지점. 테스트가 실제 sed 로 이 인자 검증. */
    static String calicoSedArgs(String podCidr) {
        return calicoSedArgs(podCidr, false);
    }

    static String calicoSedArgs(String podCidr, boolean vxlan) {
        String args = "-e 's|^\\( *\\)# - name: CALICO_IPV4POOL_CIDR|\\1- name: CALICO_IPV4POOL_CIDR|'"
                + " -e 's|^\\( *\\)#   value: \"192.168.0.0/16\"|\\1  value: \"" + podCidr + "\"|'";
        if (!vxlan) {
            return args;
        }
        // 두 값은 매니페스트에서 붙어 있고 기본이 IPIP=Always / VXLAN=Never 다. 맞바꾼다.
        return args
                + " -e '/name: CALICO_IPV4POOL_IPIP/{n;s|value: \"Always\"|value: \"Never\"|;}'"
                + " -e '/name: CALICO_IPV4POOL_VXLAN/{n;s|value: \"Never\"|value: \"Always\"|;}'";
    }

    /**
     * 파드 트래픽을 IP-in-IP 대신 VXLAN 으로 감쌀지.
     *
     * <p>IBM VPC 는 프로토콜 4(IP-in-IP) 미전달. 보안그룹 허용 상태에서도 패브릭이 폐기해
     * 노드 간 파드 통신만 차단. 노드 통신과 파드 인터넷 접속은 정상이라 원인 파악 지연.
     */
    protected boolean usesVxlanEncapsulation() {
        return false;
    }

    /** {@code Defaults.DEFAULT_POD_CIDR} 과 동일 값. 불일치 시 Calico 와 kubeadm 이 서로 다른 대역 사용. */
    protected static final String DEFAULT_POD_CIDR = "10.244.0.0/16";

    /** {@code Defaults.DEFAULT_K8S_VERSION} 과 동일 값. 불일치 시 emitter 와 다른 버전 설치. */
    protected static final String DEFAULT_K8S_VERSION = "1.31";

    private static final String CALICO_MANIFEST_URL =
            "https://raw.githubusercontent.com/projectcalico/calico/v3.28.2/manifests/calico.yaml";

    /**
     * Snapshot 의 joinToken: 없으면 fail-fast. 과거엔 공유 하드코딩 token
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
