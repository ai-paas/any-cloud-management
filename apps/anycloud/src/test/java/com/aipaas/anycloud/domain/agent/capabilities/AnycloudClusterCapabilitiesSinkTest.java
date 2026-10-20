package com.aipaas.anycloud.domain.agent.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.provisioning.capability.VmClusterGpuSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * heartbeat 의 gpu_node_count 는 드라이버 기동 전까지 0.
 *
 * <p>해당 값을 그대로 사용하면 GPU 로 생성한 클러스터가 false 로 전환돼 화면의 가속기 영역이
 * 사라짐. IBM 과 GCP 에서 발생.
 */
class AnycloudClusterCapabilitiesSinkTest {

    private final ClusterRepository clusterRepository = mock(ClusterRepository.class);
    private final VmClusterGpuSpec gpuSpec = mock(VmClusterGpuSpec.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<VmClusterGpuSpec> gpuSpecProvider = mock(ObjectProvider.class);

    private final AnycloudClusterCapabilitiesSink sink =
            new AnycloudClusterCapabilitiesSink(clusterRepository, gpuSpecProvider);

    private ClusterEntity cluster(Boolean hasGpu) {
        ClusterEntity entity = new ClusterEntity();
        entity.setHasGpuNodes(hasGpu);
        when(clusterRepository.findById("c1")).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    void gpuRequestIsNotOverwrittenByAnEarlyHeartbeat() {
        ClusterEntity entity = cluster(Boolean.TRUE);
        when(gpuSpecProvider.getIfAvailable()).thenReturn(gpuSpec);
        when(gpuSpec.requestedGpuNodes("c1")).thenReturn(true);

        sink.setHasGpuNodes("c1", false);

        assertThat(entity.getHasGpuNodes()).isTrue();
        verify(clusterRepository, never()).save(any());
    }

    @Test
    void aClusterWithoutGpuInTheRequestStillGoesFalse() {
        ClusterEntity entity = cluster(Boolean.TRUE);
        when(gpuSpecProvider.getIfAvailable()).thenReturn(gpuSpec);
        when(gpuSpec.requestedGpuNodes("c1")).thenReturn(false);

        sink.setHasGpuNodes("c1", false);

        assertThat(entity.getHasGpuNodes()).isFalse();
        verify(clusterRepository).save(entity);
    }

    @Test
    void turningItOnIsAlwaysAllowed() {
        ClusterEntity entity = cluster(Boolean.FALSE);
        when(gpuSpecProvider.getIfAvailable()).thenReturn(gpuSpec);

        sink.setHasGpuNodes("c1", true);

        assertThat(entity.getHasGpuNodes()).isTrue();
    }
}
