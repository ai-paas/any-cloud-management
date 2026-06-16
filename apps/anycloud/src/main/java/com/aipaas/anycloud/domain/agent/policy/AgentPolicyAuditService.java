package com.aipaas.anycloud.domain.agent.policy;

import java.util.Map;

/**
 * fleet-wide agent policy audit 집계 — per-cluster parallel fetch + timeout 보호 + severity 집계 + 정렬.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.agent.policy.impl.AgentPolicyAuditServiceImpl}.
 */
public interface AgentPolicyAuditService {

    /**
     * 모든 등록 cluster 의 agent policy snapshot + validator 결과 집계.
     *
     * <p>응답 구조:
     * <pre>
     * {
     *   "totalClusters": N,
     *   "scannedClusters": N - unreachable,
     *   "unreachableClusters": M,
     *   "totalWarnings": W,
     *   "bySeverity": { HIGH, MEDIUM, LOW, INFO, NONE, UNREACHABLE },
     *   "durationMs": D,
     *   "clusters": [ { clusterName, clusterStatus, highestSeverity, warningCount,
     *                   topCodes, lastReloadAt, configMapResourceVersion }, ... ]
     * }
     * </pre>
     *
     * <p>per-cluster timeout / 정렬 (HIGH 우선) / UNREACHABLE 표시 모두 impl 책임.
     */
    Map<String, Object> runFleetAudit();
}
