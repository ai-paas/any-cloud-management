package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.api.response.ClusterHealthResponse;
import com.aipaas.anycloud.domain.cluster.api.response.FleetAgentHealthResponse;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Fleet-wide cluster health 집계 — 모든 등록 cluster 의 agent health 통계 + per-cluster 상세.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.cluster.internal.ClusterFleetHealthServiceImpl}.
 */
public interface ClusterFleetHealthService {

    /**
     * 모든 등록 cluster 의 agent health 집계 + per-cluster 상세 정렬.
     *
     * <p>정렬: unhealthy with agent → noAgent → healthy. 같은 그룹 안에서는 clusterId 사전순.
     * 운영자가 문제 cluster 를 dashboard 상단에서 즉시 확인할 수 있도록.
     */
    FleetAgentHealthResponse getFleetHealth();

    /**
     * Starter 의 immutable record → anycloud API 의 Swagger-annotated DTO.
     * static — controller 의 single-cluster path 가 inject 없이 직접 호출.
     */
    static ClusterHealthResponse toDto(ClusterHealth h) {
        return ClusterHealthResponse.builder()
                .clusterId(h.clusterName())
                .healthy(h.healthy())
                .summary(h.summary())
                .agentStatus(h.agentStatus())
                .streamActive(h.streamActive())
                .lastSeenAt(
                        h.lastSeenAt() == null ? null : LocalDateTime.ofInstant(h.lastSeenAt(), ZoneId.systemDefault()))
                .lastK8sApiOkAt(
                        h.lastK8sApiOkAt() == null
                                ? null
                                : LocalDateTime.ofInstant(h.lastK8sApiOkAt(), ZoneId.systemDefault()))
                .lastSeenSecondsAgo(h.lastSeenSecondsAgo())
                .build();
    }
}
