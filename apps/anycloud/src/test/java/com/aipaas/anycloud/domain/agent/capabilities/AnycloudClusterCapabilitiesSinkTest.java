package com.aipaas.anycloud.domain.agent.capabilities;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * cluster.has_gpu_nodes update 의 dirty check / 미존재 / 예외 swallow 회귀.
 */
class AnycloudClusterCapabilitiesSinkTest extends AbstractUnitTest {

    private ClusterRepository repo;
    private AnycloudClusterCapabilitiesSink sink;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ClusterRepository.class);
        sink = new AnycloudClusterCapabilitiesSink(repo);
    }

    private ClusterEntity cluster(Boolean current) {
        ClusterEntity e = new ClusterEntity();
        e.setId("c1");
        e.setHasGpuNodes(current);
        return e;
    }

    @Test
    void setHasGpuNodes_falseToTrue_persists() {
        when(repo.findById("c1")).thenReturn(Optional.of(cluster(false)));

        sink.setHasGpuNodes("c1", true);

        ArgumentCaptor<ClusterEntity> captor = ArgumentCaptor.forClass(ClusterEntity.class);
        verify(repo).save(captor.capture());
        Assertions.assertThat(captor.getValue().getHasGpuNodes()).isTrue();
    }

    @Test
    void setHasGpuNodes_trueToFalse_persists() {
        when(repo.findById("c1")).thenReturn(Optional.of(cluster(true)));

        sink.setHasGpuNodes("c1", false);

        ArgumentCaptor<ClusterEntity> captor = ArgumentCaptor.forClass(ClusterEntity.class);
        verify(repo).save(captor.capture());
        Assertions.assertThat(captor.getValue().getHasGpuNodes()).isFalse();
    }

    @Test
    void setHasGpuNodes_sameValue_dirtyCheckSkipsSave() {
        when(repo.findById("c1")).thenReturn(Optional.of(cluster(true)));

        sink.setHasGpuNodes("c1", true); // 같은 값.

        verify(repo, never()).save(any());
    }

    @Test
    void setHasGpuNodes_nullCurrentField_treatsAsDifferent_persists() {
        // 운영 환경 데이터 안전성 — null 은 다른 값으로 간주.
        when(repo.findById("c1")).thenReturn(Optional.of(cluster(null)));

        sink.setHasGpuNodes("c1", false);

        verify(repo, times(1)).save(any());
    }

    @Test
    void setHasGpuNodes_clusterNotFound_silentNoOp() {
        when(repo.findById("ghost")).thenReturn(Optional.empty());

        sink.setHasGpuNodes("ghost", true);

        verify(repo, never()).save(any());
    }

    @Test
    void setHasGpuNodes_repoThrows_swallowsException() {
        when(repo.findById("c1")).thenThrow(new RuntimeException("DB connection lost"));

        // 호출자 (listener) 도 catch 하지만 여기서도 안전망.
        sink.setHasGpuNodes("c1", true); // throw 하면 fail.

        verify(repo, never()).save(any());
    }
}
