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
 * 종료되지 않는 작업 종료 처리.
 *
 * <p>워커 종료나 워크플로 정지 시 operation 이 RUNNING 상태로 잔존. 8일 이상 RUNNING 인 항목
 * 9건이 화면에 진행 중으로 표시된 사례 발생. 진행 중과 방치 상태를 구분할 수 없으면 목록
 * 신뢰 불가.
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
        // FAILED 만 기록하면 실제 실패와 방치 상태 구분 불가.
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
