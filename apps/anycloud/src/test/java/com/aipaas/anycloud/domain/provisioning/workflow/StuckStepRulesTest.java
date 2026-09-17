package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 메시지를 즉시 ack 하면 크래시 때 되돌아올 것이 없다.
 *
 * <p>진행 중인 채로 멈춘 클러스터를 찾아 다시 밀어주는 것이 즉시 ack 의 안전망이다. 이게 없으면
 * 재전달 루프를 메시지 유실로 바꾼 것에 지나지 않는다.
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
        // ack 는 됐는데 실행이 시작되지 못한 경우 — 크래시나 풀 거부.
        VmClusterEntity c = cluster(VmClusterStatus.BOOTSTRAPPING, null, null);

        assertThat(StuckStepRules.needsRedrive(c, NOW, STALE_AFTER)).isTrue();
    }

    @Test
    void settledClustersAreNeverPushed() {
        // 끝난 것을 다시 밀면 멀쩡한 클러스터를 부트스트랩한다.
        for (VmClusterStatus s :
                new VmClusterStatus[] {VmClusterStatus.READY, VmClusterStatus.FAILED, VmClusterStatus.DELETED}) {
            assertThat(StuckStepRules.needsRedrive(cluster(s, null, null), NOW, STALE_AFTER))
                    .as("%s", s)
                    .isFalse();
        }
    }

    @Test
    void theStepToPushMatchesTheStatus() {
        // 상태와 다른 단계를 밀면 이미 지난 단계를 다시 돌린다.
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
