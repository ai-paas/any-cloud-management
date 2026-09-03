package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterFleetHealthService;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.api.response.ClusterHealthResponse;
import com.aipaas.anycloud.domain.cluster.api.response.FleetAgentHealthResponse;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ClusterFleetHealthService} impl. 100건 단위 페이징 + 인메모리 집계 + 정렬.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClusterFleetHealthServiceImpl implements ClusterFleetHealthService {

    /**
     * Fleet 응답을 위한 cluster 페이지 사이즈. 인메모리 집계라 한 번에 다 받기보단 페이징 — cluster 수 가
     * 폭증해도 응답 크기 / 메모리 사용량을 일정하게 유지.
     */
    private static final int FLEET_CHUNK_SIZE = 100;

    private final ClusterRepository clusterRepository;
    private final AgentHealthService agentHealthService;

    @Override
    public FleetAgentHealthResponse getFleetHealth() {
        List<ClusterHealthResponse> rows = new ArrayList<>();
        int healthy = 0;
        int unhealthy = 0;
        int noAgent = 0;
        Map<String, Long> byStatus = new TreeMap<>();

        int page = 0;
        while (true) {
            Page<ClusterEntity> chunk = clusterRepository.findAll(PageRequest.of(page, FLEET_CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }
            for (ClusterEntity cluster : chunk.getContent()) {
                ClusterHealth h = agentHealthService.getHealth(cluster.getId());
                rows.add(ClusterFleetHealthService.toDto(h));

                String status = h.agentStatus() == null ? "UNKNOWN" : h.agentStatus();
                byStatus.merge(status, 1L, Long::sum);

                if (h.healthy()) {
                    healthy++;
                } else if (!h.hasAgent()) {
                    noAgent++;
                } else {
                    unhealthy++;
                }
            }
            if (!chunk.hasNext()) {
                break;
            }
            page++;
        }

        rows.sort(Comparator.comparingInt(ClusterFleetHealthServiceImpl::healthRank)
                .thenComparing(ClusterHealthResponse::clusterId, Comparator.nullsLast(String::compareTo)));

        Map<String, Long> orderedByStatus = new LinkedHashMap<>(byStatus);

        return FleetAgentHealthResponse.builder()
                .total(rows.size())
                .healthy(healthy)
                .unhealthy(unhealthy)
                .noAgent(noAgent)
                .byStatus(orderedByStatus)
                .clusters(rows)
                .build();
    }

    /**
     * Fleet 정렬 순위 — 운영자 시야에 unhealthy 가 먼저 보이도록.
     * 0 = unhealthy with agent, 1 = noAgent, 2 = healthy.
     */
    private static int healthRank(ClusterHealthResponse dto) {
        if (dto.healthy()) {
            return 2;
        }
        String s = dto.agentStatus();
        if (s == null || "NONE".equals(s) || "UNKNOWN".equals(s)) {
            return 1;
        }
        return 0;
    }
}
