package com.aipaas.anycloud.domain.agent.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.policy.impl.AgentPolicyAuditServiceImpl;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * AgentPolicyAuditServiceImpl 단위 테스트.
 */
class AgentPolicyAuditServiceImplTest extends AbstractUnitTest {

    @Mock
    KubeResourceService kubeResourceService;

    @Mock
    ClusterService clusterService;

    private AgentPolicyAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        // Synchronous executor — test 환경에서 parallel 의 비결정성 회피.
        service = new AgentPolicyAuditServiceImpl(
                kubeResourceService,
                new AgentPolicyValidator(), // 실제 validator — 룰 trigger 검증
                clusterService,
                Runnable::run);
    }

    @Test
    void runFleetAudit_mixedClusters_sortsHighFirst() {
        // ACTIVE 2개 + INACTIVE 1개 — HIGH severity 가 ACTIVE 중 1개
        ClusterEntity activeBad = ClusterEntity.builder()
                .id("c-bad")
                .status(ClusterStatus.ACTIVE)
                .clusterType("Public")
                .clusterProvider("orb")
                .build();
        ClusterEntity activeGood = ClusterEntity.builder()
                .id("c-good")
                .status(ClusterStatus.ACTIVE)
                .clusterType("Public")
                .clusterProvider("orb")
                .build();
        ClusterEntity inactive = ClusterEntity.builder()
                .id("c-down")
                .status(ClusterStatus.INACTIVE)
                .clusterType("Public")
                .clusterProvider("orb")
                .build();

        when(clusterService.getClusterEntities()).thenReturn(List.of(activeGood, activeBad, inactive));

        // c-bad: HIGH severity 유발 — allow_all_discovered + secrets 누락
        AgentPolicySnapshot bad = new AgentPolicySnapshot(
                List.of("*"),
                true,
                List.of("LIST_PODS"),
                List.of(),
                List.of(),
                false,
                new AgentPolicySnapshot.ResourcePolicy("allow_all_discovered", List.of(), List.of()),
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-bad")).thenReturn(bad);

        // c-good: secrets 가 deny — HIGH 없음 (MEDIUM 정도)
        AgentPolicySnapshot good = new AgentPolicySnapshot(
                List.of("monitoring"),
                false,
                List.of("LIST_PODS"),
                List.of(),
                List.of(),
                false,
                new AgentPolicySnapshot.ResourcePolicy(
                        "allow_all_discovered",
                        List.of(new AgentPolicySnapshot.ResourceRule("secrets", null)),
                        List.of()),
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-good")).thenReturn(good);

        Map<String, Object> body = service.runFleetAudit();

        assertThat(body).containsEntry("totalClusters", 3);
        assertThat(body).containsEntry("scannedClusters", 2);
        assertThat(body).containsEntry("unreachableClusters", 1);
        @SuppressWarnings("unchecked")
        Map<String, Integer> bySeverity = (Map<String, Integer>) body.get("bySeverity");
        assertThat(bySeverity).containsEntry("HIGH", 1);
        assertThat(bySeverity).containsEntry("UNREACHABLE", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) body.get("clusters");
        // HIGH 가 sort 의 첫 entry
        assertThat(clusters.get(0).get("clusterName")).isEqualTo("c-bad");
        assertThat(clusters.get(0).get("highestSeverity")).isEqualTo("HIGH");
        // durationMs 포함 — parallel 효과 측정용
        assertThat(body).containsKey("durationMs");
    }

    @Test
    void runFleetAudit_agentUnreachable_markedUNREACHABLE() {
        ClusterEntity active = ClusterEntity.builder()
                .id("c-1")
                .status(ClusterStatus.ACTIVE)
                .clusterType("Public")
                .clusterProvider("orb")
                .build();
        when(clusterService.getClusterEntities()).thenReturn(List.of(active));

        // agent 가 응답 안 함 — KubeRoutingException
        when(kubeResourceService.getAgentConfig("c-1")).thenThrow(new KubeRoutingException("No active agent session"));

        Map<String, Object> body = service.runFleetAudit();

        assertThat(body).containsEntry("unreachableClusters", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) body.get("clusters");
        assertThat(clusters.get(0).get("highestSeverity")).isEqualTo("UNREACHABLE");
        assertThat(clusters.get(0).get("error")).isNotNull();
    }

    @Test
    void runFleetAudit_emptyFleet_returnsZeros() {
        when(clusterService.getClusterEntities()).thenReturn(List.of());

        Map<String, Object> body = service.runFleetAudit();

        assertThat(body).containsEntry("totalClusters", 0);
        assertThat(body).containsEntry("scannedClusters", 0);
        assertThat(body).containsEntry("unreachableClusters", 0);
        assertThat(body).containsEntry("totalWarnings", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) body.get("clusters");
        assertThat(clusters).isEmpty();
    }
}
