package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterFacade.PagedClusters;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * 하나의 클러스터가 목록에 여러 번 나오지 않게 한다.
 *
 * <p>VM 으로 만든 클러스터는 agent 가 등록하면 vm 행과 registered 행을 동시에 갖고, 같은 이름으로
 * 재시도하면 실패한 vm 행이 계속 쌓인다. 화면에서는 클러스터 하나가 한 줄이어야 한다.
 */
class ClusterFacadeImplDedupeTest extends AbstractUnitTest {

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

    private ClusterFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ClusterFacadeImpl(
                vmClusterService,
                clusterService,
                operationService,
                vmClusterRepository,
                agentHealthService,
                List.of(),
                org.mapstruct.factory.Mappers.getMapper(
                        com.aipaas.anycloud.domain.operation.mapper.OperationMapper.class));
    }

    private void stubAgentHealth() {
        when(agentHealthService.getHealth(anyString()))
                .thenReturn(new ClusterHealth("c", true, "ok", "ACTIVE", true, Instant.now(), Instant.now(), 5L));
    }

    private VmClusterListItemResponse vm(String name, String status, int hour) {
        return VmClusterListItemResponse.builder()
                .clusterName(name)
                .clusterProvider("AWS")
                .region("ap-northeast-2")
                .status(status)
                .createdAt(LocalDateTime.of(2026, 6, 1, hour, 0))
                .build();
    }

    private Cluster registered(String name) {
        return new Cluster(
                name,
                "desc",
                "ACTIVE",
                "1.31",
                "VM",
                "AWS",
                "registered",
                "DONE",
                false,
                null,
                ZonedDateTime.parse("2026-06-01T00:00:00Z"),
                null);
    }

    private UnifiedClusterResponse only(PagedClusters page, String name) {
        List<UnifiedClusterResponse> hits =
                page.items().stream().filter(c -> name.equals(c.clusterName())).toList();
        assertThat(hits).as("%s 가 목록에 한 줄만 있어야 한다", name).hasSize(1);
        return hits.get(0);
    }

    @Test
    void vmAndRegisteredRowsOfOneClusterCollapseToOneRow() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of(vm("aws-01", "READY", 1)));
        stubAgentHealth();
        when(clusterService.findAllDomain()).thenReturn(List.of(registered("aws-01")));

        PagedClusters page = facade.listPaged(null, null, null, null, 20, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalEstimate()).isEqualTo(1L);
    }

    @Test
    void mergedRowReportsBothSources() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of(vm("aws-01", "READY", 1)));
        stubAgentHealth();
        when(clusterService.findAllDomain()).thenReturn(List.of(registered("aws-01")));

        UnifiedClusterResponse row = only(facade.listPaged(null, null, null, null, 20, null), "aws-01");

        assertThat(row.sources()).containsExactly("vm", "registered");
    }

    @Test
    void mergedRowKeepsAgentConnectivityFromRegisteredRow() {
        // vm 행에는 agent 정보가 없다. 합치면서 잃으면 "연동 상태" 컬럼이 비어버린다.
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of(vm("aws-01", "READY", 1)));
        stubAgentHealth();
        when(clusterService.findAllDomain()).thenReturn(List.of(registered("aws-01")));

        UnifiedClusterResponse row = only(facade.listPaged(null, null, null, null, 20, null), "aws-01");

        assertThat(row.agentConnectivity()).isEqualTo("CONNECTED");
        assertThat(row.linkedVmName()).isEqualTo("aws-01");
    }

    @Test
    void repeatedAttemptsOfOneNameCollapseToTheLiveOne() {
        when(vmClusterService.listVmClusters(null, null, null))
                .thenReturn(List.of(vm("retry", "FAILED", 3), vm("retry", "READY", 1), vm("retry", "FAILED", 2)));
        when(clusterService.findAllDomain()).thenReturn(List.of());

        UnifiedClusterResponse row = only(facade.listPaged(null, null, null, null, 20, null), "retry");

        // 최신 행이 아니라 살아 있는 행을 남긴다.
        assertThat(row.status()).isEqualTo("READY");
    }

    @Test
    void failedOnlyClusterIsHiddenByDefault() {
        // 운영할 수 없는 것이 클러스터 목록에 있으면 목록이 작업 이력이 된다.
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of(vm("dead", "FAILED", 1)));
        when(clusterService.findAllDomain()).thenReturn(List.of());

        assertThat(facade.listPaged(null, null, null, null, 20, null).items()).isEmpty();
    }

    @Test
    void explicitStatusFilterStillReachesFailedClusters() {
        // 기본에서 숨긴다고 조회 자체를 막으면 실패 원인을 볼 수 없다.
        when(vmClusterService.listVmClusters(null, null, "FAILED")).thenReturn(List.of(vm("dead", "FAILED", 1)));
        when(clusterService.findAllDomain()).thenReturn(List.of());

        assertThat(facade.listPaged(null, null, null, "FAILED", 20, null).items())
                .hasSize(1);
    }

    @Test
    void sourceFilteredListingIsNotMerged() {
        // source=vm 은 "VM 으로 만든 것만" 이라는 뜻이다. registered 를 섞으면 필터가 거짓말이 된다.
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of(vm("aws-01", "READY", 1)));

        UnifiedClusterResponse row = only(facade.listPaged("vm", null, null, null, 20, null), "aws-01");

        assertThat(row.sources()).containsExactly("vm");
    }
}
