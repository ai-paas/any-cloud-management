package com.aipaas.anycloud.domain.agent.model;

/**
 * Cluster Agent upgrade attempt 의 lifecycle.
 *
 * <pre>
 *   IDLE
 *     ↓ (operator triggers POST /v1/clusters/{name}/upgrade)
 *   PENDING — backend 가 manifest 준비
 *     ↓ (APPLY_MANIFEST RPC 응답 OK)
 *   IN_PROGRESS — K8s rolling update 중
 *     ↓ heartbeat 에서 신버전 보고  /  60min 타임아웃
 *   SUCCEEDED                      FAILED
 *     ↓                              ↓
 *   IDLE (다음 upgrade 가능)        IDLE (재시도 또는 PAUSED 처리)
 * </pre>
 *
 * <p>HA replica 가 있는 cluster 의 경우 모든 row 가 같은 status 로 sync 되지는 않는다 — orchestrator
 * 가 첫 row 만 status 갱신. agent_version 자체는 heartbeat 가 row 별로 갱신.
 */
public enum ClusterAgentUpgradeStatus {
    IDLE,
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED;

    /** terminal state — 다음 upgrade trigger 가능. */
    public boolean isTerminal() {
        return this == IDLE || this == SUCCEEDED || this == FAILED;
    }
}
