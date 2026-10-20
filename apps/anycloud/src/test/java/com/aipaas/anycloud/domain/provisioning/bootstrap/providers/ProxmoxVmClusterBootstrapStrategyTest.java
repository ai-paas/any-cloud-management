package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Proxmox 노드는 cloud-init 으로 패키지 수신 불가.
 *
 * <p>다른 CSP 는 user-data 가 부팅 중 kubeadm 을 설치하므로 부트스트랩은 대기만 수행.
 * Proxmox 는 해당 시점에 직접 설치하지 않으면 kubeadm 미설치 노드에 join 을 시도.
 */
class ProxmoxVmClusterBootstrapStrategyTest {

    private static final Pattern ENCODED = Pattern.compile("echo (\\S+) \\| base64 -d");

    private final ProxmoxVmClusterBootstrapStrategy strategy = new ProxmoxVmClusterBootstrapStrategy();

    private String decodedScript(VmClusterInternalRequestSnapshot snapshot, String role) {
        Matcher matcher = ENCODED.matcher(strategy.prepareNodeCommand(snapshot, role));
        assertThat(matcher.find()).as("준비 명령에 base64 스크립트가 없다").isTrue();
        return new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
    }

    @Test
    void proxmoxTakesPrecedenceOverTheGenericStrategy() {
        assertThat(strategy.supports("Proxmox")).isTrue();
        assertThat(strategy.supports("proxmox")).isTrue();
        assertThat(strategy.supports("IBM")).isFalse();
    }

    @Test
    void theNodeInstallsKubeadmItself() {
        String script = decodedScript(VmClusterInternalRequestSnapshot.builder().build(), "master");

        assertThat(script).contains("kubeadm").contains("containerd");
    }

    @Test
    void theScriptTravelsBase64EncodedSoQuotesSurvive() {
        // heredoc 은 따옴표와 $ 가 섞인 본문을 셸이 한 번 더 해석해 내용이 바뀐다.
        String command = strategy.prepareNodeCommand(
                VmClusterInternalRequestSnapshot.builder().build(), "worker");

        assertThat(command).contains("base64 -d | sudo bash -s");
    }

    @Test
    void masterAndWorkerGetDifferentPackages() {
        VmClusterInternalRequestSnapshot snapshot =
                VmClusterInternalRequestSnapshot.builder().build();

        assertThat(decodedScript(snapshot, "master")).contains("jq");
        assertThat(decodedScript(snapshot, "worker")).doesNotContain(" jq ");
    }

    @Test
    void theRequestedKubernetesVersionIsInstalled() {
        // 스냅샷 버전 미사용 시 노드는 기본 버전, 컨트롤 플레인은 요청 버전이 되어 join 거부.
        String script = decodedScript(
                VmClusterInternalRequestSnapshot.builder()
                        .kubernetesVersion("1.30")
                        .build(),
                "master");

        assertThat(script).contains("1.30").doesNotContain("v1.31");
    }

    @Test
    void preparationIsVerifiedAfterInstalling() {
        // 설치가 무음 실패해도 다음 단계가 진행되면 kubeadm init 시점에만 검출.
        String command = strategy.prepareNodeCommand(
                VmClusterInternalRequestSnapshot.builder().build(), "master");

        assertThat(command).endsWith(strategy.waitForPreparationCommand());
    }
}
