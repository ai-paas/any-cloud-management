package com.aipaas.anycloud.domain.agent.liveness;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ACTIVE 에서 내려오는 규칙.
 *
 * <p>등록은 상태를 올리지만 내리는 길이 없습니다. 화면만의 문제가 아닙니다. 업그레이드 대상과
 * 카탈로그가 {@code status == ACTIVE} 로 걸러 죽은 agent 를 살아 있다고 셉니다.
 */
public final class AgentLiveness {

    private AgentLiveness() {}

    /** 지우지 않고 상태만 내립니다. 지우면 무엇이 있었는지 알 수 없습니다. */
    public static ClusterAgentStatus staleStatus() {
        return ClusterAgentStatus.DEGRADED;
    }

    /**
     * @param lastSeenAt null 이면 등록만 되고 한 번도 붙은 적이 없다는 뜻입니다
     */
    public static boolean isStale(
            ClusterAgentStatus status, LocalDateTime lastSeenAt, LocalDateTime now, Duration timeout) {
        // ACTIVE 만 내린다. REVOKED 를 올리면 인증이 거부된 에이전트가 되살아난 것처럼 보인다.
        if (status != ClusterAgentStatus.ACTIVE) {
            return false;
        }
        if (lastSeenAt == null) {
            return true;
        }
        return lastSeenAt.isBefore(now.minus(timeout));
    }
}
