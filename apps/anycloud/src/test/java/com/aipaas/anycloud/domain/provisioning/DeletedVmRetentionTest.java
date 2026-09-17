package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.internal.DeletedVmClusterReaper;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * 삭제 이력에 보관 기한을 둔다.
 *
 * <p>삭제는 감사와 원인 분석을 위해 행을 남기는데 정리하는 곳이 없어 영원히 쌓인다. operation 은
 * deleteCompletedBefore 로 정리하고 있었지만 vm_cluster 에는 그런 게 없었다.
 */
class DeletedVmRetentionTest extends AbstractUnitTest {

    @Mock
    VmClusterRepository vmClusterRepository;

    private DeletedVmClusterReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new DeletedVmClusterReaper(vmClusterRepository, 90);
    }

    @Test
    void removesRowsDeletedBeforeTheRetentionWindow() {
        when(vmClusterRepository.deleteDeletedBefore(any())).thenReturn(3);

        reaper.reap();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(vmClusterRepository).deleteDeletedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(89));
    }

    @Test
    void keepsRowsInsideTheWindow() {
        // 최근 삭제 이력은 원인 분석에 쓰인다. 기한 안쪽은 건드리지 않는다.
        when(vmClusterRepository.deleteDeletedBefore(any())).thenReturn(0);

        reaper.reap();

        verify(vmClusterRepository).deleteDeletedBefore(any());
    }

    @Test
    void survivesRepositoryFailure() {
        // 정리가 실패해도 스케줄러 스레드가 죽으면 안 된다.
        when(vmClusterRepository.deleteDeletedBefore(any())).thenThrow(new IllegalStateException("boom"));

        reaper.reap();
    }

    @Test
    void retentionOfZeroDisablesCleanup() {
        // 기한 0 이하는 "정리하지 않음" 이다. 실수로 전체를 지우면 복구할 수 없다.
        new DeletedVmClusterReaper(vmClusterRepository, 0).reap();

        verify(vmClusterRepository, never()).deleteDeletedBefore(any());
    }
}
