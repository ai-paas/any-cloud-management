package com.aipaas.anycloud.domain.agent.internal;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * AdminAgentQueryService 의 동적 filter spec builder. 모든 filter null/empty 면 no-op
 * conjunction — 전체 row 반환.
 */
public final class AdminAgentSpecs {

    private AdminAgentSpecs() {}

    /**
     * @param statuses             null/empty → status filter 미적용
     * @param clusterNames         null/empty → clusterName filter 미적용
     * @param versionPrefix        null/blank → version filter 미적용
     * @param lastSeenOlderThanSec null → lastSeen filter 미적용
     * @param now                  응답 시점 (test 주입용)
     */
    public static Specification<ClusterAgentEntity> combine(
            List<ClusterAgentStatus> statuses,
            List<String> clusterNames,
            String versionPrefix,
            Long lastSeenOlderThanSec,
            LocalDateTime now) {
        Specification<ClusterAgentEntity> spec = Specification.where(null);
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("status").in(statuses));
        }
        if (clusterNames != null && !clusterNames.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("clusterName").in(clusterNames));
        }
        if (versionPrefix != null && !versionPrefix.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("agentVersion"), versionPrefix + "%"));
        }
        if (lastSeenOlderThanSec != null && now != null) {
            LocalDateTime boundary = now.minusSeconds(lastSeenOlderThanSec);
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("lastSeenAt"), boundary));
        }
        return spec;
    }
}
