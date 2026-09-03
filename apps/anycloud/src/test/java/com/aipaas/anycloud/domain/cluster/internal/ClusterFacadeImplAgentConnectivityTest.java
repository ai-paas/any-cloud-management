package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * ClusterFacadeImpl.list(source=registered) 의 agent connectivity 4단계 분기 회귀 보호.
 *
 * <p>JWT signing key bug 같은 시나리오에서 DISCONNECTED 가 정확히 노출됨을 검증.
 */
class ClusterFacadeImplAgentConnectivityTest extends AbstractUnitTest {

    @Mock
    VmClusterService vmClusterService;

    @Mock
    ClusterService clusterService;

    @Mock
    OperationService operationService;

    @Mock
    VmClusterRepository vmClusterRepository;

    @Mock
    AgentHealthService agentHealthService;

    private ClusterFacadeImpl unifiedService;

    @BeforeEach
    void setUp() {
        // 빈 provider 리스트로 시작 — list/getOne 만 검증, create 경로 미사용.
        unifiedService = new ClusterFacadeImpl(
                vmClusterService,
                clusterService,
                operationService,
                vmClusterRepository,
                agentHealthService,
                List.of(),
                org.mapstruct.factory.Mappers.getMapper(
                        com.aipaas.anycloud.domain.operation.mapper.OperationMapper.class));
    }

    @Test
    void list_registered_connected_populatesAgentFieldsForHealthyAgent() {
        ClusterEntity entity = baseEntity("c-healthy");
        when(clusterService.findAllDomain())
                .thenReturn(List.of(org.mapstruct.factory.Mappers.getMapper(
                                com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class)
                        .toDomain(entity)));
        when(agentHealthService.getHealth(eq("c-healthy")))
                .thenReturn(new ClusterHealth(
                        "c-healthy",
                        true,
                        "stream up, heartbeat 8s ago",
                        "ACTIVE",
                        true,
                        Instant.now(),
                        Instant.now(),
                        8L));

        List<UnifiedClusterResponse> result = unifiedService.list("registered", null, null, null);

        assertThat(result).hasSize(1);
        UnifiedClusterResponse dto = result.get(0);
        assertThat(dto.agentConnectivity()).isEqualTo("CONNECTED");
        assertThat(dto.agentHeartbeatSecondsAgo()).isEqualTo(8L);
        assertThat(dto.agentHealthSummary()).contains("heartbeat 8s ago");
    }

    @Test
    void list_registered_degraded_streamUpButHeartbeatStale() {
        // DEGRADED = hasAgent + !healthy + streamActive (heartbeat stale 이지만 stream 은 살아있음)
        ClusterEntity entity = baseEntity("c-degraded");
        when(clusterService.findAllDomain())
                .thenReturn(List.of(org.mapstruct.factory.Mappers.getMapper(
                                com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class)
                        .toDomain(entity)));
        when(agentHealthService.getHealth(eq("c-degraded")))
                .thenReturn(new ClusterHealth(
                        "c-degraded",
                        false,
                        "heartbeat stale (105s ago, threshold 90s)",
                        "ACTIVE",
                        true,
                        Instant.now(),
                        Instant.now(),
                        105L));

        List<UnifiedClusterResponse> result = unifiedService.list("registered", null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).agentConnectivity()).isEqualTo("DEGRADED");
        assertThat(result.get(0).agentHeartbeatSecondsAgo()).isEqualTo(105L);
    }

    @Test
    void list_registered_disconnected_jwtBugScenario() {
        // JWT bug 시나리오: agent 등록 row 는 ACTIVE 인데 pod CrashLoopBackOff → stream 끊김.
        ClusterEntity entity = baseEntity("c-disconnected");
        when(clusterService.findAllDomain())
                .thenReturn(List.of(org.mapstruct.factory.Mappers.getMapper(
                                com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class)
                        .toDomain(entity)));
        when(agentHealthService.getHealth(eq("c-disconnected")))
                .thenReturn(new ClusterHealth(
                        "c-disconnected",
                        false,
                        "agent ACTIVE in store but no live stream — likely backend restart or network issue",
                        "ACTIVE",
                        false,
                        Instant.now(),
                        Instant.now(),
                        412L));

        List<UnifiedClusterResponse> result = unifiedService.list("registered", null, null, null);

        assertThat(result).hasSize(1);
        UnifiedClusterResponse dto = result.get(0);
        // status (K8s) 는 ACTIVE 그대로 — Fabric8 ping OK
        assertThat(dto.status()).isEqualTo(ClusterStatus.ACTIVE.name());
        // agentConnectivity 만 DISCONNECTED 로 분리되어 사실 노출
        assertThat(dto.agentConnectivity()).isEqualTo("DISCONNECTED");
        assertThat(dto.agentHealthSummary()).contains("no live stream");
    }

    @Test
    void list_registered_notRegistered_whenAgentNeverInstalled() {
        ClusterEntity entity = baseEntity("c-no-agent");
        when(clusterService.findAllDomain())
                .thenReturn(List.of(org.mapstruct.factory.Mappers.getMapper(
                                com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class)
                        .toDomain(entity)));
        when(agentHealthService.getHealth(eq("c-no-agent"))).thenReturn(ClusterHealth.noAgent("c-no-agent"));

        List<UnifiedClusterResponse> result = unifiedService.list("registered", null, null, null);

        assertThat(result).hasSize(1);
        UnifiedClusterResponse dto = result.get(0);
        assertThat(dto.agentConnectivity()).isEqualTo("NOT_REGISTERED");
        assertThat(dto.agentHeartbeatSecondsAgo()).isNull();
        assertThat(dto.agentHealthSummary()).contains("no agent registered");
    }

    @Test
    void list_vm_doesNotPopulateAgentFields() {
        // vm source 응답은 agent 정보 무관 — null 이라 JsonInclude.NON_NULL 로 직렬화 제외됨.
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of());
        when(clusterService.findAllDomain()).thenReturn(List.of());

        List<UnifiedClusterResponse> result = unifiedService.list(null, null, null, null);

        assertThat(result).isEmpty();
        // agentHealthService 는 vm source 에 대해 호출 안 됨 (mock 명시 안 했으므로 verify 불필요)
    }

    private ClusterEntity baseEntity(String id) {
        return ClusterEntity.builder()
                .id(id)
                .clusterType("Public")
                .clusterProvider("orb")
                .version("v1.33.9")
                .status(ClusterStatus.ACTIVE)
                .hasGpuNodes(false)
                .build();
    }
}
