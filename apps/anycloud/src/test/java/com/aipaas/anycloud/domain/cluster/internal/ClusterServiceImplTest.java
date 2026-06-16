package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.cluster.AgentBootstrapKubeClient;
import com.aipaas.anycloud.domain.cluster.ClusterConnectivityService;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import com.aipaas.anycloud.domain.cluster.api.request.UpdateClusterDto;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * {@link ClusterServiceImpl} 회귀 lock —.
 *
 * <p>Cluster CRUD critical path 회귀 lock:
 * <ul>
 *   <li>create: duplicate 검출 (initial + race) → DUPLICATE</li>
 *   <li>create: AGENT_PENDING 상태 + provisioning_type=IMPORTED 강제</li>
 *   <li>update: PULUMI provisioning_type 거부 (route mismatch 방어)</li>
 *   <li>delete: cascade agent rows + bootstrap client cache invalidate</li>
 *   <li>get: findById exact match → metric counter 분기 </li>
 * </ul>
 */
class ClusterServiceImplTest {

    private ClusterRepository clusterRepository;
    private AgentBootstrapKubeClient bootstrapKubeClient;
    private ClusterAgentRepository clusterAgentRepository;
    private MeterRegistry meterRegistry;
    private ClusterConnectivityService connectivityService;
    private ClusterServiceImpl service;

    @BeforeEach
    void setUp() {
        clusterRepository = Mockito.mock(ClusterRepository.class);
        bootstrapKubeClient = Mockito.mock(AgentBootstrapKubeClient.class);
        clusterAgentRepository = Mockito.mock(ClusterAgentRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        connectivityService = Mockito.mock(ClusterConnectivityService.class);
        service = new ClusterServiceImpl(
                clusterRepository,
                org.mapstruct.factory.Mappers.getMapper(com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class),
                bootstrapKubeClient,
                clusterAgentRepository,
                meterRegistry,
                connectivityService);
        service.initMetrics(); // @PostConstruct 가 unit test 에서 호출되지 않음 — 명시.
    }

    // ============================================================================
    // getClusterEntity — exact match + miss
    // ============================================================================

    @Test
    void getClusterEntity_exactMatch_returnsEntity() {
        ClusterEntity entity = new ClusterEntity();
        entity.setId("orb-001");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(entity));

        ClusterEntity result = service.getClusterEntity("orb-001");

        assertThat(result.getId()).isEqualTo("orb-001");
        assertThat(meterRegistry
                        .counter("anycloud.cluster.get.total", "outcome", "exact")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void getClusterEntity_miss_throwsAndIncrementsMissCounter() {
        // O-5 회귀 lock — lenient fallback 제거 후, findById miss 시 즉시 throw.
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClusterEntity("missing")).isInstanceOf(ClusterNotFoundException.class);

        assertThat(meterRegistry
                        .counter("anycloud.cluster.get.total", "outcome", "miss")
                        .count())
                .isEqualTo(1.0);
    }

    // ============================================================================
    // createCluster
    // ============================================================================

    @Test
    void createCluster_happyPath_savesAsAgentPending_andIncrementsSuccess() {
        CreateClusterDto dto = createDto("orb-001");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.empty());

        HttpStatus result = service.createCluster(dto);

        assertThat(result).isEqualTo(HttpStatus.CREATED);

        // 회귀 lock — 모든 신규 cluster 는 AGENT_PENDING / IMPORTED / version=null.
        ArgumentCaptor<ClusterEntity> entityCaptor = ArgumentCaptor.forClass(ClusterEntity.class);
        verify(clusterRepository).save(entityCaptor.capture());
        ClusterEntity saved = entityCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ClusterStatus.AGENT_PENDING);
        assertThat(saved.getProvisioningType()).isEqualTo("IMPORTED");
        assertThat(saved.getVersion()).isNull();

        assertThat(meterRegistry
                        .counter("anycloud.cluster.create.total", "outcome", "success")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void createCluster_nullDto_throwsInvalid_incrementsCounter() {
        assertThatThrownBy(() -> service.createCluster(null)).isInstanceOf(CustomException.class);

        assertThat(meterRegistry
                        .counter("anycloud.cluster.create.total", "outcome", "invalid")
                        .count())
                .isEqualTo(1.0);
        verify(clusterRepository, never()).save(any());
    }

    @Test
    void createCluster_duplicateName_throwsDuplicate_incrementsCounter() {
        CreateClusterDto dto = createDto("existing");
        when(clusterRepository.findById("existing")).thenReturn(Optional.of(new ClusterEntity()));

        assertThatThrownBy(() -> service.createCluster(dto))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));

        assertThat(meterRegistry
                        .counter("anycloud.cluster.create.total", "outcome", "duplicate")
                        .count())
                .isEqualTo(1.0);
        verify(clusterRepository, never()).save(any());
    }

    @Test
    void createCluster_dataIntegrityViolation_uniqueKeyword_classifiedAsDuplicate() {
        // Race window — findById 후 다른 thread 가 먼저 save → DB unique constraint violation.
        CreateClusterDto dto = createDto("race-cluster");
        when(clusterRepository.findById("race-cluster")).thenReturn(Optional.empty());
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("Duplicate entry 'race-cluster' for key 'cluster.PRIMARY'"));
        Mockito.doThrow(ex).when(clusterRepository).save(any(ClusterEntity.class));

        assertThatThrownBy(() -> service.createCluster(dto))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));

        // race 도 duplicate counter — 실 인시던트 분석 시 race vs synchronous 구분 가능.
        assertThat(meterRegistry
                        .counter("anycloud.cluster.create.total", "outcome", "duplicate")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void createCluster_dataIntegrityViolation_otherCause_classifiedAsIntegrityError() {
        CreateClusterDto dto = createDto("bad-cluster");
        when(clusterRepository.findById("bad-cluster")).thenReturn(Optional.empty());
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", new RuntimeException("FK violation"));
        Mockito.doThrow(ex).when(clusterRepository).save(any(ClusterEntity.class));

        assertThatThrownBy(() -> service.createCluster(dto))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_INTEGRITY));

        assertThat(meterRegistry
                        .counter("anycloud.cluster.create.total", "outcome", "error")
                        .count())
                .isEqualTo(1.0);
    }

    // ============================================================================
    // updateCluster
    // ============================================================================

    @Test
    void updateCluster_imported_partialUpdate() {
        ClusterEntity existing = new ClusterEntity();
        existing.setId("orb-001");
        existing.setProvisioningType("IMPORTED");
        existing.setDescription("old desc");
        existing.setClusterType("k8s");
        existing.setClusterProvider("aws");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(existing));

        UpdateClusterDto update = new UpdateClusterDto();
        update.setDescription("new desc");
        // clusterType + clusterProvider 는 null → 변경 안 됨.

        service.updateCluster("orb-001", update);

        assertThat(existing.getDescription()).isEqualTo("new desc");
        assertThat(existing.getClusterType()).as("null skip").isEqualTo("k8s");
        assertThat(existing.getClusterProvider()).as("null skip").isEqualTo("aws");
        verify(clusterRepository).save(existing);
    }

    @Test
    void updateCluster_pulumi_throws_routeMismatch() {
        // VM 기반 cluster 는 /system/vm/clusters API 사용해야 — 잘못된 route 거부.
        ClusterEntity pulumi = new ClusterEntity();
        pulumi.setId("vm-001");
        pulumi.setProvisioningType("PULUMI");
        when(clusterRepository.findById("vm-001")).thenReturn(Optional.of(pulumi));

        UpdateClusterDto update = new UpdateClusterDto();
        update.setDescription("x");

        assertThatThrownBy(() -> service.updateCluster("vm-001", update))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("/system/vm/clusters");

        verify(clusterRepository, never()).save(any(ClusterEntity.class));
    }

    @Test
    void updateCluster_missing_throws_clusterNotFound() {
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCluster("missing", new UpdateClusterDto()))
                .isInstanceOf(ClusterNotFoundException.class);
    }

    // ============================================================================
    // deleteCluster — cascade + cache invalidate
    // ============================================================================

    @Test
    void deleteCluster_imported_cascadesAgentRowsAndInvalidatesCache() {
        ClusterEntity entity = new ClusterEntity();
        entity.setId("orb-001");
        entity.setProvisioningType("IMPORTED");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(entity));
        when(clusterAgentRepository.deleteByClusterName("orb-001")).thenReturn(2L);

        HttpStatus result = service.deleteCluster("orb-001");

        assertThat(result).isEqualTo(HttpStatus.OK);
        // cascade 회귀 lock — agent row 정리 안 하면 cluster 재등록 시 stale agent 충돌.
        verify(clusterAgentRepository).deleteByClusterName("orb-001");
        verify(clusterRepository).delete(entity);
        // 캐시 invalidate — 같은 ID 재등록 시 stale bootstrap client 안 됨.
        verify(bootstrapKubeClient).invalidate("orb-001");
    }

    @Test
    void deleteCluster_pulumi_throws_routeMismatch() {
        ClusterEntity pulumi = new ClusterEntity();
        pulumi.setId("vm-001");
        pulumi.setProvisioningType("PULUMI");
        when(clusterRepository.findById("vm-001")).thenReturn(Optional.of(pulumi));

        assertThatThrownBy(() -> service.deleteCluster("vm-001"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("/system/vm/clusters");

        verify(clusterRepository, never()).delete(any(ClusterEntity.class));
        verify(bootstrapKubeClient, never()).invalidate(anyString());
    }

    @Test
    void deleteCluster_missing_throws() {
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCluster("missing")).isInstanceOf(ClusterNotFoundException.class);

        verify(clusterAgentRepository, never()).deleteByClusterName(anyString());
    }

    // ============================================================================
    // testClusterConnection / refreshClusterStatus / updateAllClusterStatuses
    // ============================================================================

    @Test
    void testClusterConnection_delegatesToConnectivityService() {
        when(connectivityService.testClusterConnection("orb-001")).thenReturn(true);

        Boolean result = service.testClusterConnection("orb-001");

        assertThat(result).isTrue();
        verify(connectivityService).testClusterConnection("orb-001");
    }

    @Test
    void refreshClusterStatus_missing_propagatesClusterNotFoundException() {
        // catch blanket 제거 후 ClusterNotFoundException 이 controller 까지 그대로
        // propagate. 404 매핑 가능. 회귀 시 CustomException(500) 으로 mis-wrap 되면 frontend 진단 실패.
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshClusterStatus("missing")).isInstanceOf(ClusterNotFoundException.class);

        verify(connectivityService, never()).updateClusterVersionAndStatus(any());
    }

    @Test
    void refreshClusterStatus_connectivityRuntimeException_wrapsAsInternal() {
        // connectivity 호출 중 unexpected RuntimeException — 500 wrap 은 유지 (운영자가 root cause log
        // 확인 가능).
        ClusterEntity entity = new ClusterEntity();
        entity.setId("orb-001");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(entity));
        Mockito.doThrow(new RuntimeException("agent panic"))
                .when(connectivityService)
                .updateClusterVersionAndStatus(entity);

        assertThatThrownBy(() -> service.refreshClusterStatus("orb-001"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(com.aipaas.anycloud.common.error.enums.ErrorCode.INTERNAL_SERVER_ERROR));
    }

    @Test
    void refreshClusterStatus_connectivityCustomException_propagatesAsIs() {
        // CCC-1 회귀 lock — 이미 분류된 CustomException (예: 503 AGENT_UNAVAILABLE) 은 500 으로 wrap 되면
        // 안 됨. 그대로 propagate.
        ClusterEntity entity = new ClusterEntity();
        entity.setId("orb-001");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(entity));
        CustomException agent503 = new CustomException(
                "agent unavailable", com.aipaas.anycloud.common.error.enums.ErrorCode.AGENT_UNAVAILABLE);
        Mockito.doThrow(agent503).when(connectivityService).updateClusterVersionAndStatus(entity);

        assertThatThrownBy(() -> service.refreshClusterStatus("orb-001")).isSameAs(agent503);
    }

    @Test
    void refreshClusterStatus_happyPath_delegatesAndReturnsOk() {
        ClusterEntity entity = new ClusterEntity();
        entity.setId("orb-001");
        when(clusterRepository.findById("orb-001")).thenReturn(Optional.of(entity));

        HttpStatus result = service.refreshClusterStatus("orb-001");

        assertThat(result).isEqualTo(HttpStatus.OK);
        verify(connectivityService).updateClusterVersionAndStatus(entity);
    }

    @Test
    void updateAllClusterStatuses_delegatesToConnectivityService() {
        service.updateAllClusterStatuses();

        verify(connectivityService, times(1)).updateAllClusterStatuses();
    }

    // ============================================================================
    // helper
    // ============================================================================

    private static CreateClusterDto createDto(String name) {
        CreateClusterDto dto = new CreateClusterDto();
        dto.setClusterName(name);
        dto.setDescription("test cluster");
        dto.setClusterType("k8s");
        dto.setClusterProvider("aws");
        return dto;
    }
}
