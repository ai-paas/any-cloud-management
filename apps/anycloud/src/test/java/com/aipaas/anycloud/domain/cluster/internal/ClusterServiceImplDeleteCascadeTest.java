package com.aipaas.anycloud.domain.cluster.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.cluster.AgentBootstrapKubeClient;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

/**
 * ClusterServiceImpl.deleteCluster 의 cluster_agent cascade cleanup 회귀 보호.
 * DB 에 FK 없으므로 application-level 정리 — 본 test 가 정리 누락 조기 탐지.
 */
class ClusterServiceImplDeleteCascadeTest extends AbstractUnitTest {

    @Mock
    ClusterRepository clusterRepository;

    @Mock
    AgentBootstrapKubeClient bootstrapKubeClient;

    @Mock
    ClusterAgentRepository clusterAgentRepository;

    @Mock
    com.aipaas.anycloud.domain.cluster.ClusterConnectivityService connectivityService;

    private ClusterServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // connectivity logic 은 ClusterConnectivityService 로 위임. delete cascade test 는
        // connectivity 와 무관해서 mock 으로 충분.
        // MeterRegistry 는 실제 SimpleMeterRegistry — initMetrics() 가 호출되지 않더라도 NPE 회피.
        service = new ClusterServiceImpl(
                clusterRepository,
                org.mapstruct.factory.Mappers.getMapper(com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper.class),
                bootstrapKubeClient,
                clusterAgentRepository,
                new SimpleMeterRegistry(),
                connectivityService);
    }

    private ClusterEntity importedCluster(String id) {
        ClusterEntity c = new ClusterEntity();
        c.setId(id);
        c.setProvisioningType("IMPORTED");
        return c;
    }

    @Test
    void deleteCluster_cascadesAgentRowsThenDeletesClusterAndInvalidatesCache() {
        ClusterEntity cluster = importedCluster("c-1");
        when(clusterRepository.findById("c-1")).thenReturn(Optional.of(cluster));
        when(clusterAgentRepository.deleteByClusterName("c-1")).thenReturn(3L);

        HttpStatus result = service.deleteCluster("c-1");

        assertThat(result).isEqualTo(HttpStatus.OK);
        // Order: agent cleanup BEFORE cluster delete BEFORE cache invalidate.
        var inOrder = org.mockito.Mockito.inOrder(clusterAgentRepository, clusterRepository, bootstrapKubeClient);
        inOrder.verify(clusterAgentRepository).deleteByClusterName("c-1");
        inOrder.verify(clusterRepository).delete(cluster);
        inOrder.verify(bootstrapKubeClient).invalidate("c-1");
    }

    @Test
    void deleteCluster_zeroAgents_stillDeletesAndInvalidates() {
        ClusterEntity cluster = importedCluster("c-2");
        when(clusterRepository.findById("c-2")).thenReturn(Optional.of(cluster));
        when(clusterAgentRepository.deleteByClusterName("c-2")).thenReturn(0L);

        HttpStatus result = service.deleteCluster("c-2");

        assertThat(result).isEqualTo(HttpStatus.OK);
        verify(clusterAgentRepository, times(1)).deleteByClusterName("c-2");
        verify(clusterRepository, times(1)).delete(cluster);
        verify(bootstrapKubeClient, times(1)).invalidate("c-2");
    }

    @Test
    void deleteCluster_pulumiCluster_throwsAndSkipsCascade() {
        ClusterEntity vm = new ClusterEntity();
        vm.setId("vm-1");
        vm.setProvisioningType("PULUMI");
        when(clusterRepository.findById("vm-1")).thenReturn(Optional.of(vm));

        assertThatThrownBy(() -> service.deleteCluster("vm-1")).isInstanceOf(CustomException.class);

        // Critical: PULUMI 거부 시 cascade / delete / invalidate 모두 호출 안 됨 — side-effect 없음.
        verify(clusterAgentRepository, never()).deleteByClusterName(anyString());
        verify(clusterRepository, never()).delete(org.mockito.ArgumentMatchers.any(ClusterEntity.class));
        verify(bootstrapKubeClient, never()).invalidate(anyString());
    }

    @Test
    void deleteCluster_unknownCluster_throwsClusterNotFound() {
        // findById miss 시 즉시 throw.
        when(clusterRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCluster("ghost")).isInstanceOf(ClusterNotFoundException.class);

        verify(clusterAgentRepository, never()).deleteByClusterName(anyString());
        verify(clusterRepository, never()).delete(org.mockito.ArgumentMatchers.any(ClusterEntity.class));
    }

    // lenient (case-insensitive / trim) fallback 제거. 본 test 가 가정하던
    // findAll() + stream filter 매칭은 더 이상 작동 안 함. 운영 환경에서 row id 가 정규화되어 있으면
    // findById exact 만으로 충분. lenient 매칭이 필요해지면 controller 측에서 input normalization 권장.
    // 본 test 는 design intent 변경에 따라 삭제 — strict ID 매칭이 새 contract.
}
