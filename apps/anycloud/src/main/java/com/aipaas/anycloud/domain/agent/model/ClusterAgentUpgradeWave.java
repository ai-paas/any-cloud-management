package com.aipaas.anycloud.domain.agent.model;

/**
 * Fleet upgrade staggered rollout 의 wave 분류. cluster_agent.upgrade_wave 컬럼에 저장.
 *
 * <p>운영 의도:
 * <ul>
 *   <li>{@link #CANARY} — 1-2 cluster 만 골라 신버전 빠른 검증.</li>
 *   <li>{@link #STAGING} — dev / staging 환경. 자동화된 e2e 가 돌아가는 클러스터.</li>
 *   <li>{@link #GENERAL} — production. canary + staging 안정화 확인 후 적용.</li>
 *   <li>{@link #PAUSED} — 의도적 제외. 특정 cluster 가 신버전과 호환 안 되거나 운영 freeze 중.</li>
 * </ul>
 *
 * <p>{@code FleetUpgradeService} 가 wave 순서 (CANARY → STAGING → GENERAL) 로 차례대로 처리.
 * PAUSED 는 항상 skip — 운영자가 별도 toggle 해야 다시 들어옴.
 */
public enum ClusterAgentUpgradeWave {
    CANARY,
    STAGING,
    GENERAL,
    PAUSED;

    /** Orchestrator 가 자동 처리할 wave 인지. PAUSED 만 false. */
    public boolean isAutomatable() {
        return this != PAUSED;
    }

    /**
     * Orchestrator 처리 순서 (낮을수록 먼저). PAUSED 는 처리 안 되지만 sort 안정화용 큰 값 반환.
     */
    public int orderRank() {
        return switch (this) {
            case CANARY -> 0;
            case STAGING -> 1;
            case GENERAL -> 2;
            case PAUSED -> Integer.MAX_VALUE;
        };
    }
}
