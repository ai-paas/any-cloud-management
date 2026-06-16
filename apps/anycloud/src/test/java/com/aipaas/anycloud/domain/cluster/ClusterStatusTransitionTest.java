package com.aipaas.anycloud.domain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import org.junit.jupiter.api.Test;

/**
 * ClusterStatus (registered cluster) state transition graph 회귀 보호.
 * transition graph 변경 시 본 테스트 갱신 필요.
 */
class ClusterStatusTransitionTest {

    @Test
    void fromOrUnknown_handlesNullBlankInvalid() {
        assertThat(ClusterStatus.fromOrUnknown(null)).isEqualTo(ClusterStatus.UNKNOWN);
        assertThat(ClusterStatus.fromOrUnknown("")).isEqualTo(ClusterStatus.UNKNOWN);
        assertThat(ClusterStatus.fromOrUnknown("   ")).isEqualTo(ClusterStatus.UNKNOWN);
        assertThat(ClusterStatus.fromOrUnknown("NOT_A_VALUE")).isEqualTo(ClusterStatus.UNKNOWN);
        // case-insensitive + trim
        assertThat(ClusterStatus.fromOrUnknown("active")).isEqualTo(ClusterStatus.ACTIVE);
        assertThat(ClusterStatus.fromOrUnknown(" Inactive ")).isEqualTo(ClusterStatus.INACTIVE);
    }

    @Test
    void unknown_canMoveToAnyActiveState() {
        assertThat(ClusterStatus.UNKNOWN.canTransitionTo(ClusterStatus.AGENT_PENDING))
                .isTrue();
        assertThat(ClusterStatus.UNKNOWN.canTransitionTo(ClusterStatus.ACTIVE)).isTrue();
        assertThat(ClusterStatus.UNKNOWN.canTransitionTo(ClusterStatus.INACTIVE))
                .isTrue();
    }

    @Test
    void agentPending_canResolveToActiveOrInactive() {
        assertThat(ClusterStatus.AGENT_PENDING.canTransitionTo(ClusterStatus.ACTIVE))
                .isTrue();
        assertThat(ClusterStatus.AGENT_PENDING.canTransitionTo(ClusterStatus.INACTIVE))
                .isTrue();
        // fallback path — agent backfill 실패 시 UNKNOWN 으로 회귀 가능
        assertThat(ClusterStatus.AGENT_PENDING.canTransitionTo(ClusterStatus.UNKNOWN))
                .isTrue();
    }

    @Test
    void activeAndInactive_canFlipBackAndForth() {
        // connectivity 변동 — day-2 운영의 정상 동작
        assertThat(ClusterStatus.ACTIVE.canTransitionTo(ClusterStatus.INACTIVE)).isTrue();
        assertThat(ClusterStatus.INACTIVE.canTransitionTo(ClusterStatus.ACTIVE)).isTrue();
    }

    @Test
    void activeOrInactive_cannotRevertToAgentPending() {
        // 한 번 backfill 끝나면 AGENT_PENDING 으로 되돌아갈 일 없음 (재등록 필요)
        assertThat(ClusterStatus.ACTIVE.canTransitionTo(ClusterStatus.AGENT_PENDING))
                .isFalse();
        assertThat(ClusterStatus.INACTIVE.canTransitionTo(ClusterStatus.AGENT_PENDING))
                .isFalse();
    }

    @Test
    void sameStateTransition_isIdempotent() {
        for (ClusterStatus s : ClusterStatus.values()) {
            assertThat(s.canTransitionTo(s))
                    .as(s + " → " + s + " idempotent re-assignment allowed")
                    .isTrue();
        }
    }

    @Test
    void nullNext_rejected() {
        for (ClusterStatus s : ClusterStatus.values()) {
            assertThat(s.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void isActive_onlyTrueForActive() {
        assertThat(ClusterStatus.ACTIVE.isActive()).isTrue();
        assertThat(ClusterStatus.INACTIVE.isActive()).isFalse();
        assertThat(ClusterStatus.AGENT_PENDING.isActive()).isFalse();
        assertThat(ClusterStatus.UNKNOWN.isActive()).isFalse();
    }
}
