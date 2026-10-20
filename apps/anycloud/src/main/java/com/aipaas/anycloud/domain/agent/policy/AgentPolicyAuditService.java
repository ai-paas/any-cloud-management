package com.aipaas.anycloud.domain.agent.policy;

import java.util.Map;

/**
 * fleet-wide agent policy audit 집계 — per-cluster parallel fetch + timeout 보호 + severity 집계 + 정렬.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.agent.policy.impl.AgentPolicyAuditServiceImpl}.
 */
public interface AgentPolicyAuditService {

    /**
     * 등록된 모든 cluster 의 policy snapshot 을 모아 validator 결과를 집계합니다.
     *
     * <p>닿지 않는 cluster 는 집계에서 빼지 않고 UNREACHABLE 로 표시합니다. 조용히 빼면 "경고 없음"
     * 과 구분되지 않습니다. 목록은 HIGH 가 먼저 오도록 정렬합니다.
     */
    Map<String, Object> runFleetAudit();
}
