package com.aipaas.anycloud.domain.agent.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * GPU 노드 여부 lookup 의 정상 / 미존재 / null / 예외 안전성 회귀.
 */
class AnycloudClusterCapabilitiesTest extends AbstractUnitTest {

    private ClusterRepository repo;
    private AnycloudClusterCapabilities capabilities;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ClusterRepository.class);
        capabilities = new AnycloudClusterCapabilities(repo);
    }

    private ClusterEntity gpu(boolean hasGpu) {
        ClusterEntity e = new ClusterEntity();
        e.setId("c1");
        e.setHasGpuNodes(hasGpu);
        return e;
    }

    @Test
    void hasGpuNodes_clusterWithGpu_returnsTrue() {
        when(repo.findById("c1")).thenReturn(Optional.of(gpu(true)));
        assertThat(capabilities.hasGpuNodes("c1")).isTrue();
    }

    @Test
    void hasGpuNodes_clusterWithoutGpu_returnsFalse() {
        when(repo.findById("c1")).thenReturn(Optional.of(gpu(false)));
        assertThat(capabilities.hasGpuNodes("c1")).isFalse();
    }

    @Test
    void hasGpuNodes_clusterNotFound_returnsFalse() {
        when(repo.findById("ghost")).thenReturn(Optional.empty());
        assertThat(capabilities.hasGpuNodes("ghost")).isFalse();
    }

    @Test
    void hasGpuNodes_nullField_returnsFalse() {
        ClusterEntity e = new ClusterEntity();
        e.setId("c1");
        e.setHasGpuNodes(null); // 운영 환경 데이터 안전성.
        when(repo.findById("c1")).thenReturn(Optional.of(e));

        assertThat(capabilities.hasGpuNodes("c1")).isFalse();
    }

    @Test
    void hasGpuNodes_repoThrows_returnsFalseSwallowed() {
        when(repo.findById("c1")).thenThrow(new RuntimeException("DB connection lost"));
        // auto-installer 흐름 보호 — DB 실패가 listener 까지 새어 나가면 안 됨.
        assertThat(capabilities.hasGpuNodes("c1")).isFalse();
    }
}
