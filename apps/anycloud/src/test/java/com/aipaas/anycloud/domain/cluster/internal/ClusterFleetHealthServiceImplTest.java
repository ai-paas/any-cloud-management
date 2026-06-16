package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.api.response.FleetAgentHealthResponse;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

/**
 * {@link ClusterFleetHealthServiceImpl} 회귀 lock —.
 *
 * <p>Dashboard 상단 노출 정렬 + counting + byStatus 집계의 정확성이 운영자 incident triage 시간 직결.
 * 회귀 시 unhealthy cluster 가 healthy cluster 아래에 묻혀 SLA 위반 가능.
 */
class ClusterFleetHealthServiceImplTest {

    private ClusterRepository clusterRepository;
    private AgentHealthService agentHealthService;
    private ClusterFleetHealthServiceImpl service;

    @BeforeEach
    void setUp() {
        clusterRepository = Mockito.mock(ClusterRepository.class);
        agentHealthService = Mockito.mock(AgentHealthService.class);
        service = new ClusterFleetHealthServiceImpl(clusterRepository, agentHealthService);
    }

    // ============================================================================
    // Sorting — unhealthy first, then noAgent, then healthy
    // ============================================================================

    @Test
    void getFleetHealth_sortsUnhealthyFirst_thenNoAgent_thenHealthy() {
        // 의도적 mixed 순서로 repository 가 반환.
        stubClusters(cluster("z-healthy"), cluster("a-noagent"), cluster("m-unhealthy"));
        when(agentHealthService.getHealth("z-healthy")).thenReturn(healthy("z-healthy"));
        when(agentHealthService.getHealth("a-noagent")).thenReturn(ClusterHealth.noAgent("a-noagent"));
        when(agentHealthService.getHealth("m-unhealthy")).thenReturn(unhealthyWithAgent("m-unhealthy"));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.clusters())
                .as("정렬: unhealthy(with agent) → noAgent → healthy")
                .extracting(c -> c.clusterId(), c -> c.healthy())
                .containsExactly(tuple("m-unhealthy", false), tuple("a-noagent", false), tuple("z-healthy", true));
    }

    @Test
    void getFleetHealth_sameRank_sortedByClusterIdAscending() {
        stubClusters(cluster("c-h"), cluster("a-h"), cluster("b-h"));
        when(agentHealthService.getHealth("a-h")).thenReturn(healthy("a-h"));
        when(agentHealthService.getHealth("b-h")).thenReturn(healthy("b-h"));
        when(agentHealthService.getHealth("c-h")).thenReturn(healthy("c-h"));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.clusters()).extracting(c -> c.clusterId()).containsExactly("a-h", "b-h", "c-h");
    }

    // ============================================================================
    // Counting
    // ============================================================================

    @Test
    void getFleetHealth_countsHealthyUnhealthyNoAgent() {
        stubClusters(cluster("h1"), cluster("h2"), cluster("u1"), cluster("n1"), cluster("n2"));
        when(agentHealthService.getHealth("h1")).thenReturn(healthy("h1"));
        when(agentHealthService.getHealth("h2")).thenReturn(healthy("h2"));
        when(agentHealthService.getHealth("u1")).thenReturn(unhealthyWithAgent("u1"));
        when(agentHealthService.getHealth("n1")).thenReturn(ClusterHealth.noAgent("n1"));
        when(agentHealthService.getHealth("n2")).thenReturn(ClusterHealth.noAgent("n2"));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.healthy()).isEqualTo(2);
        assertThat(result.unhealthy()).isEqualTo(1);
        assertThat(result.noAgent()).isEqualTo(2);
    }

    @Test
    void getFleetHealth_emptyRepository_returnsZeros() {
        when(clusterRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.total()).isZero();
        assertThat(result.healthy()).isZero();
        assertThat(result.unhealthy()).isZero();
        assertThat(result.noAgent()).isZero();
        assertThat(result.clusters()).isEmpty();
    }

    // ============================================================================
    // byStatus aggregation
    // ============================================================================

    @Test
    void getFleetHealth_byStatus_aggregatesAgentStatusCounts() {
        stubClusters(cluster("a"), cluster("b"), cluster("c"), cluster("d"));
        when(agentHealthService.getHealth("a")).thenReturn(withStatus("a", "ACTIVE", true));
        when(agentHealthService.getHealth("b")).thenReturn(withStatus("b", "ACTIVE", true));
        when(agentHealthService.getHealth("c")).thenReturn(withStatus("c", "DEGRADED", false));
        when(agentHealthService.getHealth("d")).thenReturn(ClusterHealth.noAgent("d"));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.byStatus())
                .containsEntry("ACTIVE", 2L)
                .containsEntry("DEGRADED", 1L)
                .containsEntry("NONE", 1L);
    }

    @Test
    void getFleetHealth_nullAgentStatus_bucketedAsUnknown() {
        stubClusters(cluster("a"));
        when(agentHealthService.getHealth("a"))
                .thenReturn(new ClusterHealth("a", false, "no signal", null, false, null, null, null));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.byStatus()).containsEntry("UNKNOWN", 1L);
    }

    // ============================================================================
    // Pagination — chunks of 100
    // ============================================================================

    @Test
    void getFleetHealth_paginatesIn100Chunks() {
        // 250 cluster — 3 page (100 + 100 + 50) 호출.
        List<ClusterEntity> page1 = clustersRange(0, 100);
        List<ClusterEntity> page2 = clustersRange(100, 200);
        List<ClusterEntity> page3 = clustersRange(200, 250);

        when(clusterRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(page1, org.springframework.data.domain.PageRequest.of(0, 100), 250))
                .thenReturn(new PageImpl<>(page2, org.springframework.data.domain.PageRequest.of(1, 100), 250))
                .thenReturn(new PageImpl<>(page3, org.springframework.data.domain.PageRequest.of(2, 100), 250));

        // 모든 cluster 가 healthy 라고 stub.
        when(agentHealthService.getHealth(any())).thenAnswer(inv -> healthy(inv.getArgument(0)));

        FleetAgentHealthResponse result = service.getFleetHealth();

        assertThat(result.total()).isEqualTo(250);
        assertThat(result.healthy()).isEqualTo(250);
    }

    // ============================================================================
    // helpers
    // ============================================================================

    private void stubClusters(ClusterEntity... clusters) {
        Page<ClusterEntity> page = new PageImpl<>(List.of(clusters));
        when(clusterRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page)
                .thenReturn(new PageImpl<>(List.of()));
    }

    private static ClusterEntity cluster(String id) {
        ClusterEntity e = new ClusterEntity();
        e.setId(id);
        return e;
    }

    private static List<ClusterEntity> clustersRange(int from, int to) {
        List<ClusterEntity> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(cluster(String.format("c-%03d", i)));
        }
        return list;
    }

    private static ClusterHealth healthy(String name) {
        return new ClusterHealth(name, true, "stream up", "ACTIVE", true, Instant.now(), Instant.now(), 5L);
    }

    private static ClusterHealth unhealthyWithAgent(String name) {
        return new ClusterHealth(
                name, false, "heartbeat stale", "DEGRADED", false, Instant.now().minusSeconds(900), null, 900L);
    }

    private static ClusterHealth withStatus(String name, String status, boolean healthyFlag) {
        return new ClusterHealth(name, healthyFlag, "test", status, healthyFlag, Instant.now(), Instant.now(), 1L);
    }
}
