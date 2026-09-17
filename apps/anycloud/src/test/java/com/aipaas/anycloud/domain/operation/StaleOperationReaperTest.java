package com.aipaas.anycloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.operation.internal.StaleOperationReaper;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * 끝나지 않는 작업을 닫는다.
 *
 * <p>워커가 죽거나 워크플로가 멈추면 operation 이 RUNNING 인 채로 남는다. 실제로 8일 넘게
 * RUNNING 인 항목이 9건 있었고, 화면에서는 아직 진행 중인 것처럼 보였다. 진행 중과 방치된 것을
 * 구분할 수 없으면 목록을 믿을 수 없다.
 */
class StaleOperationReaperTest extends AbstractUnitTest {

    @Mock
    OperationRepository operationRepository;

    @Mock
    OperationService operationService;

    private StaleOperationReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new StaleOperationReaper(operationRepository, operationService, Duration.ofHours(2));
    }

    private OperationEntity running(String id) {
        OperationEntity e = new OperationEntity();
        e.setId(id);
        e.setState(OperationState.RUNNING);
        e.setCurrentStep("BOOTSTRAP");
        return e;
    }

    @Test
    void failsOperationsOlderThanTheWindow() {
        when(operationRepository.findStaleActive(any(), any())).thenReturn(List.of(running("op-1")));

        reaper.reap();

        verify(operationService).fail(org.mockito.ArgumentMatchers.eq("op-1"), anyString());
    }

    @Test
    void saysWhyItWasClosed() {
        // "FAILED" 만 남으면 진짜 실패와 방치를 구분할 수 없다.
        when(operationRepository.findStaleActive(any(), any())).thenReturn(List.of(running("op-1")));

        reaper.reap();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(operationService).fail(anyString(), reason.capture());
        assertThat(reason.getValue()).contains("BOOTSTRAP").containsIgnoringCase("응답");
    }

    @Test
    void asksForOperationsOlderThanTheConfiguredWindow() {
        when(operationRepository.findStaleActive(any(), any())).thenReturn(List.of());

        reaper.reap();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(operationRepository).findStaleActive(any(), cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusMinutes(119));
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        when(operationRepository.findStaleActive(any(), any())).thenReturn(List.of(running("op-1"), running("op-2")));
        when(operationService.fail(org.mockito.ArgumentMatchers.eq("op-1"), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        reaper.reap();

        verify(operationService).fail(org.mockito.ArgumentMatchers.eq("op-2"), anyString());
    }

    @Test
    void doesNothingWhenNothingIsStale() {
        when(operationRepository.findStaleActive(any(), any())).thenReturn(List.of());

        reaper.reap();

        verify(operationService, never()).fail(anyString(), anyString());
    }
}
