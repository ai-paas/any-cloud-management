package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import java.util.List;

/**
 * Fleet-wide upgrade 의 wave 순차 처리 진입점.
 *
 * <p>운영자 trigger ({@code submit}) + 강제 중단 ({@code abort}) 두 가지 API. 실제 background
 * scheduler ({@code @Scheduled drive()}) 는 구현체의 책임 — interface 노출 안 함.
 *
 * <p>구현체: {@link FleetUpgradeOrchestratorImpl}. mock 기반 controller test 는 본 interface 의
 * submit/abort 만 stub 가능 (scheduler 는 production runtime 에서만 동작).
 */
public interface FleetUpgradeOrchestrator {

    /**
     * 운영자가 trigger 한 fleet upgrade. PLANNED row 생성 후 scheduler 가 처리.
     *
     * @param targetImage      target docker image
     * @param waves            처리할 wave 목록 (CANARY → STAGING → GENERAL 순서로 자동 정렬, PAUSED 거부)
     * @param concurrency      wave 안 동시 진행 cluster 수 (1-20)
     * @param failureThreshold 단일 wave failure rate (%) — 초과 시 자동 abort (1-100)
     * @param createdBy        audit (REST caller 의 user id, nullable)
     * @return 새로 생성된 PLANNED status 의 run row
     */
    FleetUpgradeRunEntity submit(
            String targetImage,
            List<ClusterAgentUpgradeWave> waves,
            int concurrency,
            int failureThreshold,
            String createdBy);

    /**
     * 운영자가 명시적으로 abort. RUNNING / PAUSED 일 때만 의미. 이미 terminal 이면 no-op.
     */
    FleetUpgradeRunEntity abort(String runId, String reason);
}
