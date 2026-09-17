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
 * 락을 못 잡는 것은 일시적인 상황이지 메시지를 버릴 이유가 아니다.
 *
 * <p>BOOTSTRAP 은 자기 락을 쥔 채 다음 단계를 발행한다. VERIFY 가 그 순간 도착하면 락을 못 잡는데,
 * 조용히 반환하면 ack 되어 사라진다 — 워크플로가 영원히 BOOTSTRAP 에 머문다. 실제로 그랬다.
 *
 * <p>되돌리면 잠시 뒤 다시 와서 그때는 락이 비어 있다.
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
        // 이미 끝난 메시지를 되돌리면 영원히 돈다.
        when(messageGuard.shouldProcess(any())).thenReturn(false);

        dispatcher.dispatch(message(VmClusterWorkflowStep.VERIFY), () -> {}, (m, s, e) -> {});

        verify(executor, never()).execute(any());
        verify(processingLock, never()).acquire(any(), any());
    }

    @Test
    void destroyNeverWaitsForAnotherStep() {
        when(messageGuard.shouldProcess(any())).thenReturn(true);
        // 삭제는 선점한다. 되돌릴 일이 없다.
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
