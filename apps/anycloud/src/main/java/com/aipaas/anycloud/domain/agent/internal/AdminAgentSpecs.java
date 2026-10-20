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
     * @param statuses             null 이거나 비면 status 로 거르지 않습니다
     * @param clusterNames         null 이거나 비면 clusterName 으로 거르지 않습니다
     * @param versionPrefix        null 이거나 비면 version 으로 거르지 않습니다
     * @param lastSeenOlderThanSec null 이면 lastSeen 으로 거르지 않습니다
     * @param now                  응답 시점 (test 주입용)
     */
    public static Specification<ClusterAgentEntity> combine(
            List<ClusterAgentStatus> statuses,
            List<String> clusterNames,
            String versionPrefix,
            Long lastSeenOlderThanSec,
            LocalDateTime now) {
        /*
         * cluster 가 사라진 agent 행은 숨깁니다. cluster 를 삭제해도 행이 남아, 목록이 없는
         * cluster 의 DEGRADED 로 채워지면 동작 중인 agent 를 찾기 어렵습니다.
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
