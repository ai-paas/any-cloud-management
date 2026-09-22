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
 * heartbeat 의 gpu_node_count 는 드라이버가 올라오기 전에는 0 이다.
 *
 * <p>그 값을 그대로 쓰면 GPU 로 만든 클러스터가 false 로 뒤집혀 화면에서 가속기 영역이 사라진다.
 * IBM 과 GCP 에서 실제로 그랬다.
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
