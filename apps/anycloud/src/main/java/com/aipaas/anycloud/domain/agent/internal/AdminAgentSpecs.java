package com.aipaas.anycloud.domain.agent.internal;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * AdminAgentQueryService 의 동적 filter spec builder. 모든 filter null/empty 면 no-op
 * conjunction: 전체 row 반환.
 */
public final class AdminAgentSpecs {

    private AdminAgentSpecs() {}

    /**
     * @param statuses             null 또는 빈 값이면 status 필터 미적용
     * @param clusterNames         null 또는 빈 값이면 clusterName 필터 미적용
     * @param versionPrefix        null 또는 빈 값이면 version 필터 미적용
     * @param lastSeenOlderThanSec null 이면 lastSeen 필터 미적용
     * @param now                  응답 시점 (test 주입용)
     */
    public static Specification<ClusterAgentEntity> combine(
            List<ClusterAgentStatus> statuses,
            List<String> clusterNames,
            String versionPrefix,
            Long lastSeenOlderThanSec,
            LocalDateTime now) {
        /*
         * cluster 가 삭제된 agent 행은 제외. cluster 삭제 후에도 행이 남아 목록이 존재하지 않는
         * cluster 의 DEGRADED 로 채워지면 동작 중인 agent 식별 곤란.
         */
        Specification<ClusterAgentEntity> spec = (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var cluster = sub.from(ClusterEntity.class);
            sub.select(cb.literal(1L)).where(cb.equal(cluster.get("id"), root.get("clusterName")));
            return cb.exists(sub);
        };
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
