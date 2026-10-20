package com.aipaas.anycloud.domain.agent.policy;

import java.util.Map;

/**
 * fleet-wide agent policy audit 집계: per-cluster parallel fetch + timeout 보호 + severity 집계 + 정렬.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.agent.policy.impl.AgentPolicyAuditServiceImpl}.
 */
public interface AgentPolicyAuditService {

    /**
     * 등록된 전체 cluster 의 policy snapshot 수집 후 validator 결과 집계.
     *
     * <p>접근 불가 cluster 는 집계에서 제외하지 않고 UNREACHABLE 로 표시. 제외 시 경고 없음과
     * 구분 불가. 목록은 HIGH 우선 정렬.
     */
    Map<String, Object> runFleetAudit();
}
