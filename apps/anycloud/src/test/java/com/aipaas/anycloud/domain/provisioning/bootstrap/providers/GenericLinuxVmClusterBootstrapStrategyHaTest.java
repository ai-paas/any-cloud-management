package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HA control-plane (multi-master) 변경의 regression 보호.
 *
 * <p>Single master (masterCount=1, 기본) 는 legacy 동작 그대로 — kubeadm init 에
 * {@code --control-plane-endpoint} / {@code --upload-certs} 가 들어가면 안 된다.
 * Multi-master (masterCount>=2) 는 둘 다 들어가야 하고, control-plane join 명령은
 * {@code --control-plane} + {@code --certificate-key} 를 포함해야 한다.
 */
class GenericLinuxVmClusterBootstrapStrategyHaTest extends AbstractUnitTest {

    private final GenericLinuxVmClusterBootstrapStrategy strategy = new GenericLinuxVmClusterBootstrapStrategy();

    private VmClusterInternalRequestSnapshot snapshot(Map<String, String> providerConfig) {
        LinkedHashMap<String, String> config =
                providerConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providerConfig);
        //  정상 생성 경로는 backend 가 항상 random joinToken 을 snapshot 에 영속화 — 그
        // invariant 를 test fixture 에도 반영 (없으면 strategy 가 fail-fast).
        config.putIfAbsent("anycloud-k8s:joinToken", "test1.token0123456789ab");
        return VmClusterInternalRequestSnapshot.builder().providerConfig(config).build();
    }

    @Test
    void initializeMaster_singleMaster_default_omitsControlPlaneEndpoint() {
        // masterCount unset → 기본 1 (single master). Legacy 동작 유지.
        String cmd = strategy.initializeMasterCommand(snapshot(Map.of()));

        assertThat(cmd).doesNotContain("--control-plane-endpoint");
        assertThat(cmd).doesNotContain("--upload-certs");
        assertThat(cmd).contains("kubeadm init");
        assertThat(cmd).contains("--pod-network-cidr=");
        assertThat(cmd).contains("--token=");
    }

    @Test
    void initializeMaster_explicitSingleMaster_omitsHaFlags() {
        String cmd = strategy.initializeMasterCommand(snapshot(Map.of("anycloud-k8s:masterCount", "1")));

        assertThat(cmd).doesNotContain("--control-plane-endpoint");
        assertThat(cmd).doesNotContain("--upload-certs");
    }

    @Test
    void initializeMaster_multiMaster_addsControlPlaneEndpointAndUploadCerts() {
        String cmd = strategy.initializeMasterCommand(snapshot(Map.of("anycloud-k8s:masterCount", "3")));

        assertThat(cmd).contains("--control-plane-endpoint=\"${LOCAL_IP}:6443\"");
        assertThat(cmd).contains("--upload-certs");
        // 기본 kubeadm 인자는 그대로 유지.
        assertThat(cmd).contains("--apiserver-advertise-address=\"${LOCAL_IP}\"");
        assertThat(cmd).contains("--pod-network-cidr=");
    }

    @Test
    void initializeMaster_invalidMasterCount_defaultsToSingleMaster() {
        // Strategy 자체는 validation 책임이 없고 (ProvisioningConfigRules 가 reject), 잘못된 값이
        // 흘러들어와도 fail-safe 로 single master 모드로 작동.
        String cmd = strategy.initializeMasterCommand(snapshot(Map.of("anycloud-k8s:masterCount", "not-a-number")));

        assertThat(cmd).doesNotContain("--control-plane-endpoint");
        assertThat(cmd).doesNotContain("--upload-certs");
    }

    @Test
    void uploadCertsCommand_default_returnsKubeadmInitPhase() {
        // Interface default 가 그대로 동작하는지 확인 (override 없이도 lead master 에서 cert key
        // 추출 가능).
        String cmd = strategy.uploadCertsCommand();

        assertThat(cmd).contains("kubeadm init phase upload-certs");
        assertThat(cmd).contains("--upload-certs");
        assertThat(cmd).contains("tail -n 1");
    }

    @Test
    void buildControlPlaneJoinCommand_emitsControlPlaneAndCertificateKey() {
        String cmd = strategy.buildControlPlaneJoinCommand(
                snapshot(Map.of("anycloud-k8s:joinToken", "my.token.here")), "10.0.0.10", "deadbeef", "cert-key-abc");

        assertThat(cmd).contains("kubeadm join '10.0.0.10':6443");
        assertThat(cmd).contains("--token='my.token.here'");
        assertThat(cmd).contains("--discovery-token-ca-cert-hash sha256:'deadbeef'");
        assertThat(cmd).contains("--control-plane");
        assertThat(cmd).contains("--certificate-key 'cert-key-abc'");
        // 멱등성 — 이미 join 된 노드는 skip.
        assertThat(cmd).contains("if [ ! -f /etc/kubernetes/kubelet.conf ]");
        // kubeconfig 복사로 extra master 도 kubectl 사용 가능.
        assertThat(cmd).contains("cp /etc/kubernetes/admin.conf /root/.kube/config");
    }

    @Test
    void initializeMaster_missingJoinToken_failsFast() {
        //  공유 하드코딩 token fallback 제거 — snapshot 에 joinToken 이 없으면 즉시 실패해야
        // 한다 (silent 공유 token 으로 cluster 횡 이동 공격면이 생기는 것 차단).
        VmClusterInternalRequestSnapshot noToken = VmClusterInternalRequestSnapshot.builder()
                .providerConfig(new LinkedHashMap<>())
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.initializeMasterCommand(noToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("joinToken missing");
    }

    @Test
    void buildWorkerJoinCommand_doesNotEmitControlPlaneFlags() {
        // Worker join 은 control-plane 플래그가 없어야 한다 (regression: HA 변경이 worker 명령에
        // 영향 안 끼치는지).
        String cmd =
                strategy.buildWorkerJoinCommand(snapshot(Map.of("anycloud-k8s:joinToken", "tok")), "10.0.0.10", "hash");

        assertThat(cmd).contains("kubeadm join '10.0.0.10':6443");
        assertThat(cmd).doesNotContain("--control-plane");
        assertThat(cmd).doesNotContain("--certificate-key");
    }
}
