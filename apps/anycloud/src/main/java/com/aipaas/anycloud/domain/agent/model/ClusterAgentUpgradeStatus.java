package com.aipaas.anycloud.domain.agent.model;

/**
 * agent upgrade 시도의 lifecycle 입니다.
 *
 * <pre>
 *   IDLE
 *     ↓ (operator triggers POST /v1/clusters/{name}/upgrade)
 *   PENDING: backend 가 manifest 준비
 *     ↓ (APPLY_MANIFEST RPC 응답 OK)
 *   IN_PROGRESS: K8s rolling update 중
 *     ↓ heartbeat 에서 신버전 보고  /  60min 타임아웃
 *   SUCCEEDED                      FAILED
 *     ↓                              ↓
 *   IDLE (다음 upgrade 가능)        IDLE (재시도 또는 PAUSED 처리)
 * </pre>
 *
 * <p>replica 가 여럿이면 row 마다 status 가 같지 않습니다. orchestrator 는 첫 row 만 갱신하고
 * {@code agent_version} 은 각 row 의 heartbeat 가 따로 올립니다.
 */
public enum ClusterAgentUpgradeStatus {
    IDLE,
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED;

    /** terminal state: 다음 upgrade trigger 가능. */
    public boolean isTerminal() {
        return this == IDLE || this == SUCCEEDED || this == FAILED;
    }
}
