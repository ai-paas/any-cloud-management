package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterFleetHealthService;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.internal.ClusterFleetHealthServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link ClusterHealthController} 단위 테스트. AgentHealthService / ClusterRepository 만
 * mock. Spring context 미사용.
 */
class ClusterHealthControllerTest extends AbstractUnitTest {

    @Mock
    AgentHealthService agentHealthService;

    @Mock
    ClusterRepository clusterRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // controller 가 ClusterFleetHealthService 위임. test 는 real service +
        // dependency mock (집계 + 정렬은 production fidelity 가 중요해서 mock 대신 real).
        ClusterFleetHealthService fleetHealthService =
                new ClusterFleetHealthServiceImpl(clusterRepository, agentHealthService);
        mvc = MockMvcBuilders.standaloneSetup(new ClusterHealthController(agentHealthService, fleetHealthService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void single_returnsHealthyCluster() throws Exception {
        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        when(agentHealthService.getHealth("demo-aws-01"))
                .thenReturn(new ClusterHealth(
                        "demo-aws-01",
                        true,
                        "stream up, heartbeat 5s ago",
                        "ACTIVE",
                        true,
                        now.minusSeconds(5),
                        now.minusSeconds(7),
                        5L));

        mvc.perform(get("/v1/clusters/demo-aws-01/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clusterId").value("demo-aws-01"))
                .andExpect(jsonPath("$.data.healthy").value(true))
                .andExpect(jsonPath("$.data.agentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.streamActive").value(true))
                .andExpect(jsonPath("$.data.lastSeenSecondsAgo").value(5));
    }

    @Test
    void single_returnsNoAgent() throws Exception {
        when(agentHealthService.getHealth("ghost-cluster")).thenReturn(ClusterHealth.noAgent("ghost-cluster"));

        mvc.perform(get("/v1/clusters/ghost-cluster/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthy").value(false))
                .andExpect(jsonPath("$.data.agentStatus").value("NONE"))
                .andExpect(jsonPath("$.data.streamActive").value(false));
    }

    @Test
    void fleet_aggregatesHealthyUnhealthyAndNoAgent() throws Exception {
        ClusterEntity c1 = ClusterEntity.builder()
                .id("alpha")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        ClusterEntity c2 = ClusterEntity.builder()
                .id("bravo")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        ClusterEntity c3 = ClusterEntity.builder()
                .id("charlie")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        Page<ClusterEntity> page = new PageImpl<>(List.of(c1, c2, c3), Pageable.unpaged(), 3);
        when(clusterRepository.findAll(any(Pageable.class))).thenReturn(page);

        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        when(agentHealthService.getHealth(eq("alpha")))
                .thenReturn(new ClusterHealth(
                        "alpha", true, "ok", "ACTIVE", true, now.minusSeconds(3), now.minusSeconds(3), 3L));
        when(agentHealthService.getHealth(eq("bravo")))
                .thenReturn(new ClusterHealth(
                        "bravo", false, "stale", "DEGRADED", false, now.minusSeconds(120), null, 120L));
        when(agentHealthService.getHealth(eq("charlie"))).thenReturn(ClusterHealth.noAgent("charlie"));

        mvc.perform(get("/v1/agents/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.healthy").value(1))
                .andExpect(jsonPath("$.data.unhealthy").value(1))
                .andExpect(jsonPath("$.data.noAgent").value(1))
                .andExpect(jsonPath("$.data.byStatus.ACTIVE").value(1))
                .andExpect(jsonPath("$.data.byStatus.DEGRADED").value(1))
                .andExpect(jsonPath("$.data.byStatus.NONE").value(1))
                // 정렬: unhealthy(bravo) → noAgent(charlie) → healthy(alpha)
                .andExpect(jsonPath("$.data.clusters[0].clusterId").value("bravo"))
                .andExpect(jsonPath("$.data.clusters[1].clusterId").value("charlie"))
                .andExpect(jsonPath("$.data.clusters[2].clusterId").value("alpha"));
    }

    @Test
    void fleet_emptyClusterList_returnsZeros() throws Exception {
        Page<ClusterEntity> empty = new PageImpl<>(List.of(), Pageable.unpaged(), 0);
        when(clusterRepository.findAll(any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/v1/agents/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.healthy").value(0))
                .andExpect(jsonPath("$.data.unhealthy").value(0))
                .andExpect(jsonPath("$.data.noAgent").value(0))
                .andExpect(jsonPath("$.data.clusters.length()").value(0));
    }
}
