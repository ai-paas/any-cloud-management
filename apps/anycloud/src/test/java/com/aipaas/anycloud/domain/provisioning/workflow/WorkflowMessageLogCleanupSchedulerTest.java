package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.WorkflowMessageLogRepository;
import com.aipaas.anycloud.domain.provisioning.workflow.internal.WorkflowMessageLogCleanupScheduler;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

/** 메시지마다 한 행씩 쌓이는데 정리하는 곳이 없었다. */
class WorkflowMessageLogCleanupSchedulerTest extends AbstractUnitTest {

    @Mock
    WorkflowMessageLogRepository repository;

    @InjectMocks
    WorkflowMessageLogCleanupScheduler scheduler;

    private void configure(boolean enabled, int retentionDays) {
        ReflectionTestUtils.setField(scheduler, "enabled", enabled);
        ReflectionTestUtils.setField(scheduler, "retentionDays", retentionDays);
    }

    @Test
    void deletesRowsOlderThanTheRetentionWindow() {
        configure(true, 30);
        when(repository.deleteCreatedBefore(any())).thenReturn(7);

        scheduler.sweep();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteCreatedBefore(cutoff.capture());
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue())
                .isBefore(LocalDateTime.now().minusDays(29))
                .isAfter(LocalDateTime.now().minusDays(31));
    }

    @Test
    void doesNothingWhenDisabled() {
        configure(false, 30);

        scheduler.sweep();

        verify(repository, never()).deleteCreatedBefore(any());
    }

    @Test
    void zeroRetentionMeansKeepEverything() {
        // 0 을 "즉시 삭제"로 읽으면 설정 실수 한 번에 이력이 통째로 사라진다.
        configure(true, 0);

        scheduler.sweep();

        verify(repository, never()).deleteCreatedBefore(any());
    }

    @Test
    void aFailedSweepDoesNotPropagate() {
        // 정리 실패가 워크플로를 막으면 안 된다.
        configure(true, 30);
        when(repository.deleteCreatedBefore(any())).thenThrow(new RuntimeException("db down"));

        scheduler.sweep();
    }
}
