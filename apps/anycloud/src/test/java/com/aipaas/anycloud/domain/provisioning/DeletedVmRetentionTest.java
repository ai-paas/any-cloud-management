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
 * 삭제 이력에 보관 기한 적용.
 *
 * <p>삭제 시 감사와 원인 분석용 행을 기록하나 정리 주체가 없어 무한 누적. operation 은
 * deleteCompletedBefore 로 정리하고 있었으나 vm_cluster 에는 해당 경로 부재.
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
        // 최근 삭제 이력은 원인 분석에 사용. 기한 내 항목은 유지.
        when(vmClusterRepository.deleteDeletedBefore(any())).thenReturn(0);

        reaper.reap();

        verify(vmClusterRepository).deleteDeletedBefore(any());
    }

    @Test
    void survivesRepositoryFailure() {
        // 정리 실패로 스케줄러 스레드 종료 금지.
        when(vmClusterRepository.deleteDeletedBefore(any())).thenThrow(new IllegalStateException("boom"));

        reaper.reap();
    }

    @Test
    void retentionOfZeroDisablesCleanup() {
        // 기한 0 이하는 정리 비활성. 전체 삭제 시 복구 불가.
        new DeletedVmClusterReaper(vmClusterRepository, 0).reap();

        verify(vmClusterRepository, never()).deleteDeletedBefore(any());
    }
}
