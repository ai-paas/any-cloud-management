package com.aipaas.anycloud.domain.agent.liveness;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * ACTIVE 에서 내려오는 길이 없었다.
 *
 * <p>클러스터가 사라져 하트비트가 끊겨도 행은 ACTIVE 로 남는다. 화면만의 문제가 아니다 —
 * 업그레이드 대상과 카탈로그가 status == ACTIVE 로 거르기 때문에 죽은 에이전트를 살아 있다고 센다.
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
        // lastSeenAt 이 비어 있는데 ACTIVE 면 등록만 되고 붙은 적이 없다는 뜻이다.
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
        // 지우면 무엇이 있었는지 알 수 없다. 상태만 내린다.
        assertThat(AgentLiveness.staleStatus()).isEqualTo(ClusterAgentStatus.DEGRADED);
    }

    @Test
    void exactlyAtTheTimeoutIsNotYetStale() {
        assertThat(AgentLiveness.isStale(ClusterAgentStatus.ACTIVE, NOW.minus(TIMEOUT), NOW, TIMEOUT))
                .isFalse();
    }
}
