package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterFacade.PagedClusters;
import com.aipaas.anycloud.domain.cluster.ClusterService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ClusterFacadeImplPagedTest extends AbstractUnitTest {

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

    private List<VmClusterListItemResponse> vmRows(int count) {
        List<VmClusterListItemResponse> rows = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        IntStream.range(0, count)
                .forEach(i -> rows.add(VmClusterListItemResponse.builder()
                        .clusterName("vm-" + i)
                        .clusterProvider("AWS")
                        .status("READY")
                        .createdAt(base.plusHours(i))
                        .build()));
        return rows;
    }

    private List<Cluster> registeredRows(int count) {
        List<Cluster> rows = new ArrayList<>();
        ZonedDateTime base = ZonedDateTime.parse("2026-06-15T00:00:00Z");
        IntStream.range(0, count)
                .forEach(i -> rows.add(new Cluster(
                        "reg-" + i,
                        "desc",
                        "ACTIVE",
                        "1.29",
                        "VM",
                        "AWS",
                        "registered",
                        "DONE",
                        false,
                        null,
                        base.plusHours(i),
                        null)));
        return rows;
    }

    private void stubHealthForAllRegistered(List<Cluster> rows) {
        rows.forEach(c -> when(agentHealthService.getHealth(c.id()))
                .thenReturn(new ClusterHealth(c.id(), true, "ok", "ACTIVE", true, Instant.now(), Instant.now(), 5L)));
    }

    @Test
    void vmSource_firstPage_yieldsPageSizeItemsWithVmNextToken() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(7));

        PagedClusters result = unifiedService.listPaged("vm", null, null, null, 3, null);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).clusterName()).isEqualTo("vm-0");
        assertThat(result.nextPageToken()).isEqualTo("vm:3");
        assertThat(result.totalEstimate()).isEqualTo(7L);
    }

    @Test
    void vmSource_lastPage_yieldsNullNextToken() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(5));

        PagedClusters result = unifiedService.listPaged("vm", null, null, null, 10, null);

        assertThat(result.items()).hasSize(5);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void registeredSource_pagesIndependently() {
        List<Cluster> rows = registeredRows(4);
        when(clusterService.findAllDomain()).thenReturn(rows);
        stubHealthForAllRegistered(rows);

        PagedClusters result = unifiedService.listPaged("registered", null, null, null, 2, "registered:2");

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).clusterName()).isEqualTo("reg-2");
        assertThat(result.nextPageToken()).isNull();
        assertThat(result.totalEstimate()).isEqualTo(4L);
    }

    @Test
    void noSourceFilter_mergesBothSourcesByCreatedAtDescending() {
        // Registered rows seeded with later createdAt than vm rows so chronological merge places
        // registered first — assertion documents the descending-by-createdAt contract.
        List<Cluster> registered = registeredRows(2);
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(2));
        when(clusterService.findAllDomain()).thenReturn(registered);
        stubHealthForAllRegistered(registered);

        PagedClusters result = unifiedService.listPaged(null, null, null, null, 10, null);

        assertThat(result.items()).hasSize(4);
        assertThat(result.items().get(0).clusterName()).isEqualTo("reg-1");
        assertThat(result.items().get(1).clusterName()).isEqualTo("reg-0");
        assertThat(result.items().get(2).clusterName()).isEqualTo("vm-1");
        assertThat(result.items().get(3).clusterName()).isEqualTo("vm-0");
        assertThat(result.nextPageToken()).isNull();
        assertThat(result.totalEstimate()).isEqualTo(4L);
    }

    @Test
    void noSourceFilter_pagedAcrossMergedResult() {
        List<Cluster> registered = registeredRows(2);
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(2));
        when(clusterService.findAllDomain()).thenReturn(registered);
        stubHealthForAllRegistered(registered);

        PagedClusters page1 = unifiedService.listPaged(null, null, null, null, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextPageToken()).isEqualTo("2");

        PagedClusters page2 = unifiedService.listPaged(null, null, null, null, 2, page1.nextPageToken());
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.nextPageToken()).isNull();
    }

    @Test
    void invalidTokenFallsBackToFirstPage() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(3));

        PagedClusters result = unifiedService.listPaged(null, null, null, null, 10, "garbage-not-a-number");

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).clusterName()).isEqualTo("vm-2");
    }

    @Test
    void emptySourceReturnsEmptyItemsAndNullToken() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(List.of());

        PagedClusters result = unifiedService.listPaged("vm", null, null, null, 10, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void cursorOffsetBeyondTotalYieldsEmptyItems() {
        when(vmClusterService.listVmClusters(null, null, null)).thenReturn(vmRows(2));

        PagedClusters result = unifiedService.listPaged("vm", null, null, null, 5, "vm:10");

        assertThat(result.items()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }
}
