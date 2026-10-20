package com.aipaas.anycloud.domain.agent.liveness;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ACTIVE 하향 전이 규칙.
 *
 * <p>등록은 상태를 상향하나 하향 경로 부재. 업그레이드 대상과 카탈로그가
 * {@code status == ACTIVE} 로 필터링하므로 중단된 agent 가 정상으로 집계.
 */
public final class AgentLiveness {

    private AgentLiveness() {}

    /** 삭제 없이 상태만 하향. 삭제 시 이력 확인 불가. */
    public static ClusterAgentStatus staleStatus() {
        return ClusterAgentStatus.DEGRADED;
    }

    /**
     * @param lastSeenAt null 이면 등록만 완료되고 접속 이력 없음
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
