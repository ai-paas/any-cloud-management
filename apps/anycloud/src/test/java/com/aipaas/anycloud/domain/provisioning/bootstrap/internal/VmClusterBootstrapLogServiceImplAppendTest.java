package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * UX #2 — bootstrapLog append mode 회귀 보호.
 *
 * <p>이전 동작: BOOTSTRAP step 진입 시 {@code setBootstrapLog(null)} 호출 → retry 시
 * 이전 시도 로그 손실. 변경 후: appendAttemptMarker 가 마커만 추가하고 기존 log 보존.
 */
class VmClusterBootstrapLogServiceImplAppendTest extends AbstractUnitTest {

    private final VmClusterRemoteAccessService remote = Mockito.mock(VmClusterRemoteAccessService.class);
    private final VmClusterBootstrapLogServiceImpl service = new VmClusterBootstrapLogServiceImpl(remote);

    @Test
    void appendAttemptMarker_firstAttempt_addsHeader_andNoLeadingNewline() {
        VmClusterEntity cluster = new VmClusterEntity();
        // log 가 null 인 첫 attempt — leading newline 없이 marker 만 노출.

        service.appendAttemptMarker(cluster, 1);

        assertThat(cluster.getBootstrapLog()).isNotNull();
        assertThat(cluster.getBootstrapLog()).startsWith("=== attempt 1 — ");
        assertThat(cluster.getBootstrapLog()).contains(" ===");
    }

    @Test
    void appendAttemptMarker_secondAttempt_keepsExistingLog() {
        VmClusterEntity cluster = new VmClusterEntity();
        cluster.setBootstrapLog("=== attempt 1 — 2026-01-01 00:00:00 ===\nprior diagnostics\n");

        service.appendAttemptMarker(cluster, 2);

        assertThat(cluster.getBootstrapLog()).contains("prior diagnostics");
        assertThat(cluster.getBootstrapLog()).contains("=== attempt 1");
        assertThat(cluster.getBootstrapLog()).contains("=== attempt 2 — ");
        // attempt 2 마커가 attempt 1 보다 뒤에 와야 함.
        int idx1 = cluster.getBootstrapLog().indexOf("=== attempt 1");
        int idx2 = cluster.getBootstrapLog().indexOf("=== attempt 2");
        assertThat(idx2).isGreaterThan(idx1);
    }

    @Test
    void appendAttemptMarker_capsAtMaxSize_preservingNewestAttempts() {
        // 256KB cap 을 넘는 누적 로그 시뮬레이션 — 오래된 attempt 부터 잘려야 함.
        VmClusterEntity cluster = new VmClusterEntity();
        StringBuilder huge = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            huge.append("=== attempt ").append(i).append(" — 2026-01-01 00:00:00 ===\n");
            huge.append("x".repeat(40_000)).append("\n"); // 각 attempt ~40KB.
        }
        cluster.setBootstrapLog(huge.toString());

        service.appendAttemptMarker(cluster, 11);

        String result = cluster.getBootstrapLog();
        assertThat(result.length()).isLessThanOrEqualTo(256 * 1024 + 100); // sentinel 길이 여유.
        assertThat(result).contains("(older attempts truncated)");
        // 최신 marker 는 보존되어야 함.
        assertThat(result).contains("=== attempt 11 — ");
        // 가장 오래된 attempt 1 은 잘려야 함.
        assertThat(result).doesNotContain("=== attempt 1 — 2026-01-01");
    }

    @Test
    void appendDiagnostics_nullDiagnostics_noop() {
        // SSH 접근 실패 시 collectBootstrapLog 가 null 반환 — entity 변경 없어야 함.
        VmClusterEntity cluster = new VmClusterEntity();
        cluster.setBootstrapLog("=== attempt 1 ===\nexisting");
        // outputs 에 master IP 없으면 collect 가 null 반환.

        service.appendDiagnostics(cluster, java.util.Map.of());

        assertThat(cluster.getBootstrapLog()).isEqualTo("=== attempt 1 ===\nexisting");
    }
}
