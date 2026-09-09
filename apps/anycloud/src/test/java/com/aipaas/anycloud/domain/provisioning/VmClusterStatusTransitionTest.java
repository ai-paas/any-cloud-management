package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import org.junit.jupiter.api.Test;

/**
 * VmClusterStatus state transition graph 회귀 보호.
 */
class VmClusterStatusTransitionTest {

    @Test
    void requested_canStartProvisioning() {
        assertThat(VmClusterStatus.REQUESTED.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isTrue();
    }

    @Test
    void provisioning_canAdvanceOrFailOrBlock() {
        assertThat(VmClusterStatus.PROVISIONING.canTransitionTo(VmClusterStatus.BOOTSTRAPPING))
                .isTrue();
        assertThat(VmClusterStatus.PROVISIONING.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();
        assertThat(VmClusterStatus.PROVISIONING.canTransitionTo(VmClusterStatus.BLOCKED))
                .isTrue();
        assertThat(VmClusterStatus.PROVISIONING.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
    }

    @Test
    void ready_canRevertForScaleOrUpgrade() {
        // day-2 ops 는 명시적 SCALING / UPGRADING 으로 분기 (PROVISIONING 재사용 X).
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.SCALING))
                .isTrue();
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.UPGRADING))
                .isTrue();
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();
        // READY → PROVISIONING 은 invalid — 명시 SCALING/UPGRADING 사용.
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isFalse();
    }

    @Test
    void scalingAndUpgrading_endInReadyOrFailedOrDeleting() {
        // SCALING / UPGRADING 의 valid exit
        assertThat(VmClusterStatus.SCALING.canTransitionTo(VmClusterStatus.READY))
                .isTrue();
        assertThat(VmClusterStatus.SCALING.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();
        assertThat(VmClusterStatus.SCALING.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
        assertThat(VmClusterStatus.UPGRADING.canTransitionTo(VmClusterStatus.READY))
                .isTrue();
        assertThat(VmClusterStatus.UPGRADING.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();

        // invalid — day-2 ops 가 직접 다른 step 으로 점프 금지
        assertThat(VmClusterStatus.SCALING.canTransitionTo(VmClusterStatus.UPGRADING))
                .isFalse();
        assertThat(VmClusterStatus.SCALING.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isFalse();
    }

    @Test
    void ready_cannotJumpToBootstrappingOrVerifying() {
        // READY 에서 중간 step 으로 점프는 invalid — 항상 SCALING/UPGRADING/DELETING 만 가능
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.BOOTSTRAPPING))
                .isFalse();
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.VERIFYING))
                .isFalse();
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.REQUESTED))
                .isFalse();
    }

    @Test
    void deleted_isTerminal_noOutgoingTransitions() {
        for (VmClusterStatus s : VmClusterStatus.values()) {
            if (s == VmClusterStatus.DELETED) continue;
            assertThat(VmClusterStatus.DELETED.canTransitionTo(s))
                    .as("DELETED → " + s + " must be forbidden (terminal)")
                    .isFalse();
        }
        // idempotent same-state OK
        assertThat(VmClusterStatus.DELETED.canTransitionTo(VmClusterStatus.DELETED))
                .isTrue();
    }

    @Test
    void blocked_canBeManuallyRetriedOrDestroyed() {
        assertThat(VmClusterStatus.BLOCKED.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isTrue();
        assertThat(VmClusterStatus.BLOCKED.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
        assertThat(VmClusterStatus.BLOCKED.canTransitionTo(VmClusterStatus.REQUESTED))
                .isTrue();
        // can NOT jump directly to READY without re-running workflow
        assertThat(VmClusterStatus.BLOCKED.canTransitionTo(VmClusterStatus.READY))
                .isFalse();
    }

    @Test
    void failed_canRetryOrCleanup() {
        assertThat(VmClusterStatus.FAILED.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isTrue();
        assertThat(VmClusterStatus.FAILED.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
        assertThat(VmClusterStatus.FAILED.canTransitionTo(VmClusterStatus.BLOCKED))
                .isTrue();
        // FAILED → READY 직접 점프는 invalid
        assertThat(VmClusterStatus.FAILED.canTransitionTo(VmClusterStatus.READY))
                .isFalse();
    }

    @Test
    void deleting_endsInDeletedOrFailed() {
        assertThat(VmClusterStatus.DELETING.canTransitionTo(VmClusterStatus.DELETED))
                .isTrue();
        assertThat(VmClusterStatus.DELETING.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();
        // 다른 상태로 되돌아갈 수 없음
        assertThat(VmClusterStatus.DELETING.canTransitionTo(VmClusterStatus.READY))
                .isFalse();
        assertThat(VmClusterStatus.DELETING.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isFalse();
    }

    @Test
    void sameStateTransition_isIdempotent() {
        for (VmClusterStatus s : VmClusterStatus.values()) {
            assertThat(s.canTransitionTo(s))
                    .as(s + " → " + s + " must be allowed (idempotent re-assignment)")
                    .isTrue();
        }
    }

    @Test
    void nullNext_rejected() {
        for (VmClusterStatus s : VmClusterStatus.values()) {
            assertThat(s.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void categoryFlags_consistent() {
        assertThat(VmClusterStatus.DELETED.isTerminal()).isTrue();
        assertThat(VmClusterStatus.BLOCKED.isBlocked()).isTrue();
        assertThat(VmClusterStatus.PROVISIONING.isInProgress()).isTrue();
        assertThat(VmClusterStatus.READY.isInProgress()).isFalse();
        assertThat(VmClusterStatus.READY.isTerminal()).isFalse();
    }

    @Test
    void verifying_canDegradeWhenRequiredComponentsMissing() {
        assertThat(VmClusterStatus.VERIFYING.canTransitionTo(VmClusterStatus.DEGRADED))
                .isTrue();
    }

    @Test
    void degraded_canRecoverToReady() {
        // 조정 루프가 수렴시키면 운영자 개입 없이 READY 로 돌아온다.
        assertThat(VmClusterStatus.DEGRADED.canTransitionTo(VmClusterStatus.READY))
                .isTrue();
    }

    @Test
    void ready_canDegradeOnDrift() {
        assertThat(VmClusterStatus.READY.canTransitionTo(VmClusterStatus.DEGRADED))
                .isTrue();
    }

    @Test
    void degraded_canFailOrDelete() {
        assertThat(VmClusterStatus.DEGRADED.canTransitionTo(VmClusterStatus.FAILED))
                .isTrue();
        assertThat(VmClusterStatus.DEGRADED.canTransitionTo(VmClusterStatus.DELETING))
                .isTrue();
    }

    @Test
    void degraded_cannotJumpBackIntoProvisioning() {
        // day-2 ops 는 READY 에서만 시작한다. DEGRADED 에서 스케일하면 수렴 상태를 잃는다.
        assertThat(VmClusterStatus.DEGRADED.canTransitionTo(VmClusterStatus.PROVISIONING))
                .isFalse();
        assertThat(VmClusterStatus.DEGRADED.canTransitionTo(VmClusterStatus.SCALING))
                .isFalse();
    }

    @Test
    void degraded_isNeitherTerminalNorBlockedNorInProgress() {
        // 자동 진행 중이 아니라 수렴 대기다. isInProgress 에 넣으면 중복 워크플로우 가드가 오작동한다.
        assertThat(VmClusterStatus.DEGRADED.isTerminal()).isFalse();
        assertThat(VmClusterStatus.DEGRADED.isBlocked()).isFalse();
        assertThat(VmClusterStatus.DEGRADED.isInProgress()).isFalse();
    }

    @Test
    void degraded_hasDetailMessage() {
        assertThat(VmClusterStatus.DEGRADED.detailMessage()).isNotBlank();
    }

    @Test
    void degraded_doesNotBlockVerifyReentry() {
        // 재시도가 목적인 상태라 VERIFY 재진입을 막으면 안 된다.
        assertThat(VmClusterWorkflowStep.VERIFY.isStaleForStatus(VmClusterStatus.DEGRADED))
                .isFalse();
    }

    @Test
    void degraded_blocksProvisionAndBootstrapReentry() {
        assertThat(VmClusterWorkflowStep.PROVISION.isStaleForStatus(VmClusterStatus.DEGRADED))
                .isTrue();
        assertThat(VmClusterWorkflowStep.BOOTSTRAP.isStaleForStatus(VmClusterStatus.DEGRADED))
                .isTrue();
    }
}
