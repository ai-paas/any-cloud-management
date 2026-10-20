package com.aipaas.anycloud.domain.agent.liveness;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * ACTIVE 하향 전이 경로 부재.
 *
 * <p>클러스터 삭제로 하트비트가 끊겨도 행은 ACTIVE 로 잔존. 업그레이드 대상과 카탈로그가
 * status == ACTIVE 로 필터링하므로 중단된 에이전트가 정상으로 집계.
 */
class AgentLivenessTest extends AbstractUnitTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    @Test
    void recentHeartbeatStaysActive() {
        LocalDateTime seen = NOW.minusMinutes(1);

        assertThat(AgentLiveness.isStale(ClusterAgentStatus.ACTIVE, seen, NOW, TIMEOUT))
                .isFalse();
    }

    @Test
    void silenceBeyondTheTimeoutIsStale() {
        LocalDateTime seen = NOW.minusMinutes(9);

        assertThat(AgentLiveness.isStale(ClusterAgentStatus.ACTIVE, seen, NOW, TIMEOUT))
                .isTrue();
    }

    @Test
    void anAgentThatNeverReportedIsStale() {
        // lastSeenAt 이 비고 ACTIVE 면 등록만 완료되고 접속 이력 없음.
        assertThat(AgentLiveness.isStale(ClusterAgentStatus.ACTIVE, null, NOW, TIMEOUT))
                .isTrue();
    }

    @Test
    void onlyActiveAgentsAreDemoted() {
        // REVOKED 를 DEGRADED 로 올리면 인증이 거부된 에이전트가 되살아난 것처럼 보인다.
        LocalDateTime old = NOW.minusDays(5);

        assertThat(AgentLiveness.isStale(ClusterAgentStatus.REVOKED, old, NOW, TIMEOUT))
                .isFalse();
        assertThat(AgentLiveness.isStale(ClusterAgentStatus.FAILED, old, NOW, TIMEOUT))
                .isFalse();
        assertThat(AgentLiveness.isStale(ClusterAgentStatus.REGISTERED, old, NOW, TIMEOUT))
                .isFalse();
    }

    @Test
    void demotedAgentsAreNotDeleted() {
        // 삭제 시 이력 확인 불가. 상태만 하향.
        assertThat(AgentLiveness.staleStatus()).isEqualTo(ClusterAgentStatus.DEGRADED);
    }

    @Test
    void exactlyAtTheTimeoutIsNotYetStale() {
        assertThat(AgentLiveness.isStale(ClusterAgentStatus.ACTIVE, NOW.minus(TIMEOUT), NOW, TIMEOUT))
                .isFalse();
    }
}
