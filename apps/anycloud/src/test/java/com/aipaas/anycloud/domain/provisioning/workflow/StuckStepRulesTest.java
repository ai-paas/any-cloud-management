package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 메시지 즉시 ack 구조에서는 크래시 시 재전달 대상 부재.
 *
 * <p>진행 중 상태로 정지한 클러스터를 탐색해 재투입하는 것이 즉시 ack 의 보완 수단. 이 경로가
 * 없으면 재전달 루프를 메시지 유실로 교체한 결과.
 */
class StuckStepRulesTest extends AbstractUnitTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(180);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    private VmClusterEntity cluster(VmClusterStatus status, LocalDateTime processingStartedAt, String messageId) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId("vmc-1");
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        e.setProcessingStartedAt(processingStartedAt);
        e.setProcessingMessageId(messageId);
        return e;
    }

    @Test
    void workStillInsideItsBudgetIsLeftAlone() {
        VmClusterEntity c = cluster(VmClusterStatus.BOOTSTRAPPING, NOW.minusMinutes(30), "m1");

        assertThat(StuckStepRules.needsRedrive(c, NOW, STALE_AFTER)).isFalse();
    }

    @Test
    void workThatOutlivedItsBudgetIsPushedAgain() {
        VmClusterEntity c = cluster(VmClusterStatus.BOOTSTRAPPING, NOW.minusMinutes(200), "m1");

        assertThat(StuckStepRules.needsRedrive(c, NOW, STALE_AFTER)).isTrue();
    }

    @Test
    void anInFlightClusterWithNoOwnerIsPushedAgain() {
        // ack 는 됐는데 실행이 시작되지 못한 경우: 크래시나 풀 거부.
        VmClusterEntity c = cluster(VmClusterStatus.BOOTSTRAPPING, null, null);

        assertThat(StuckStepRules.needsRedrive(c, NOW, STALE_AFTER)).isTrue();
    }

    @Test
    void settledClustersAreNeverPushed() {
        // 완료 항목을 재투입하면 정상 클러스터에 부트스트랩 재실행.
        for (VmClusterStatus s :
                new VmClusterStatus[] {VmClusterStatus.READY, VmClusterStatus.FAILED, VmClusterStatus.DELETED}) {
            assertThat(StuckStepRules.needsRedrive(cluster(s, null, null), NOW, STALE_AFTER))
                    .as("%s", s)
                    .isFalse();
        }
    }

    @Test
    void theStepToPushMatchesTheStatus() {
        // 상태와 다른 단계를 투입하면 완료 단계를 재실행.
        assertThat(StuckStepRules.stepFor(VmClusterStatus.PROVISIONING)).isEqualTo(VmClusterWorkflowStep.PROVISION);
        assertThat(StuckStepRules.stepFor(VmClusterStatus.BOOTSTRAPPING)).isEqualTo(VmClusterWorkflowStep.BOOTSTRAP);
        assertThat(StuckStepRules.stepFor(VmClusterStatus.VERIFYING)).isEqualTo(VmClusterWorkflowStep.VERIFY);
        assertThat(StuckStepRules.stepFor(VmClusterStatus.DELETING)).isEqualTo(VmClusterWorkflowStep.DESTROY);
    }

    @Test
    void aSettledStatusHasNoStepToPush() {
        assertThat(StuckStepRules.stepFor(VmClusterStatus.READY)).isNull();
    }
}
