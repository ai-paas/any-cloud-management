package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.workflow.internal.WorkflowStepDispatcher;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 락 획득 실패는 일시적 상황이며 메시지 폐기 사유 아님.
 *
 * <p>BOOTSTRAP 이 락을 보유한 채 다음 단계를 발행. VERIFY 가 그 시점에 도착하면 락 획득에
 * 실패하는데, 무음 반환 시 ack 되어 소실. 워크플로가 BOOTSTRAP 에 고착되는 사례 발생.
 *
 * <p>재큐 시 잠시 후 재도착하며 그 시점에는 락이 해제된 상태.
 */
class LockContentionIsRetriedTest extends AbstractUnitTest {

    @Mock
    ThreadPoolTaskExecutor executor;

    @Mock
    WorkflowMessageGuard messageGuard;

    @Mock
    WorkflowMessageLogService logService;

    @Mock
    ProcessingLock processingLock;

    private WorkflowStepDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new WorkflowStepDispatcher(executor, messageGuard, logService, processingLock);
    }

    private VmClusterWorkflowMessage message(VmClusterWorkflowStep step) {
        return VmClusterWorkflowMessage.builder()
                .messageId("m1")
                .vmClusterId("c1")
                .clusterName("demo")
                .step(step)
                .build();
    }

    @Test
    void aBusyClusterSendsTheMessageBackInsteadOfDroppingIt() {
        when(messageGuard.shouldProcess(any())).thenReturn(true);
        when(processingLock.acquire(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> dispatcher.dispatch(message(VmClusterWorkflowStep.VERIFY), () -> {}, (m, s, e) -> {}))
                .isInstanceOf(WorkflowStepBusyException.class);

        verify(executor, never()).execute(any());
    }

    @Test
    void aFreeClusterRunsTheWork() {
        when(messageGuard.shouldProcess(any())).thenReturn(true);
        when(processingLock.acquire(any(), any())).thenReturn(true);

        dispatcher.dispatch(message(VmClusterWorkflowStep.VERIFY), () -> {}, (m, s, e) -> {});

        verify(executor).execute(any());
    }

    @Test
    void anAlreadyProcessedMessageIsNotSentBack() {
        // 처리 완료 메시지를 재큐하면 무한 반복.
        when(messageGuard.shouldProcess(any())).thenReturn(false);

        dispatcher.dispatch(message(VmClusterWorkflowStep.VERIFY), () -> {}, (m, s, e) -> {});

        verify(executor, never()).execute(any());
        verify(processingLock, never()).acquire(any(), any());
    }

    @Test
    void destroyNeverWaitsForAnotherStep() {
        when(messageGuard.shouldProcess(any())).thenReturn(true);
        // 삭제는 소유권 선점 방식. 재큐 대상 아님.
        when(processingLock.preempt(any(), any())).thenReturn(true);

        dispatcher.dispatch(message(VmClusterWorkflowStep.DESTROY), () -> {}, (m, s, e) -> {});

        verify(executor).execute(any());
        verify(processingLock, never()).acquire(any(), any());
    }

    @Test
    void theBusyExceptionSaysWhichClusterWasBusy() {
        assertThat(new WorkflowStepBusyException("demo", VmClusterWorkflowStep.VERIFY).getMessage())
                .contains("demo")
                .contains("VERIFY");
    }
}
