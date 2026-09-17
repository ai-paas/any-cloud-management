package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.workflow.ProcessingLock;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageGuard;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageLogService;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowStepBusyException;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 단계 실행을 소비자 스레드에서 떼어낸다.
 *
 * <p>BOOTSTRAP 최악 예산이 170분인데 AMQP ack 창은 그보다 짧다. 소비자 스레드 위에서 끝까지 돌면
 * 창을 넘긴 순간 채널이 닫히고 메시지가 되돌아와 같은 작업이 처음부터 다시 시작한다.
 *
 * <p>여기서는 소유권만 잡고 바로 돌려준다 — 메시지는 즉시 ack 된다. 크래시로 실행이 사라지는 것은
 * {@code StuckWorkflowStepReaper} 가 다시 민다.
 */
@Slf4j
@Component
public class WorkflowStepDispatcher {

    private final ThreadPoolTaskExecutor executor;
    private final WorkflowMessageGuard messageGuard;
    private final WorkflowMessageLogService workflowMessageLogService;
    private final ProcessingLock processingLock;

    public WorkflowStepDispatcher(
            @Qualifier(com.aipaas.anycloud.configuration.properties.AsyncConfig.BOOTSTRAP_EXECUTOR)
                    ThreadPoolTaskExecutor executor,
            WorkflowMessageGuard messageGuard,
            WorkflowMessageLogService workflowMessageLogService,
            ProcessingLock processingLock) {
        this.executor = executor;
        this.messageGuard = messageGuard;
        this.workflowMessageLogService = workflowMessageLogService;
        this.processingLock = processingLock;
    }

    /**
     * 가드와 소유권은 동기로 확인한다. 비동기로 미루면 두 메시지가 동시에 제출된다.
     *
     * @param onFailure 실패 기록. 호출부가 자기 맥락으로 남긴다
     */
    public void dispatch(VmClusterWorkflowMessage message, Runnable work, FailureRecorder onFailure) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        // 삭제는 앞 단계 뒤에 줄을 서지 않는다. 사용자의 탈출구라 몇 시간을 기다리게 하면 안 된다.
        boolean acquired = message.getStep() == VmClusterWorkflowStep.DESTROY
                ? processingLock.preempt(message.getVmClusterId(), message.getMessageId())
                : processingLock.acquire(message.getVmClusterId(), message.getMessageId());
        if (!acquired) {
            // 버리면 ack 되어 사라진다. 앞 단계가 자기 락을 쥔 채 다음 단계를 발행하므로
            // 이 자리는 늘 걸리고, 워크플로가 그 자리에 멈춘다. 되돌리면 잠시 뒤 다시 온다.
            throw new WorkflowStepBusyException(message.getClusterName(), message.getStep());
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            executor.execute(() -> run(message, work, onFailure, startedAt));
        } catch (RejectedExecutionException e) {
            // 자리가 없으면 잡은 것을 놓고 메시지를 되돌린다. 삼키면 아무도 이 단계를 돌리지 않는다.
            processingLock.release(message.getVmClusterId(), message.getMessageId());
            log.warn("워크플로 실행 풀이 가득 차 메시지를 되돌린다 (cluster={})", message.getClusterName());
            throw e;
        }
    }

    private void run(
            VmClusterWorkflowMessage message, Runnable work, FailureRecorder onFailure, LocalDateTime startedAt) {
        try {
            work.run();
            workflowMessageLogService.recordProcessed(message, startedAt);
        } catch (Exception e) {
            onFailure.record(message, startedAt, e);
        } finally {
            messageGuard.markProcessed(message);
            processingLock.release(message.getVmClusterId(), message.getMessageId());
        }
    }

    @FunctionalInterface
    public interface FailureRecorder {
        void record(VmClusterWorkflowMessage message, LocalDateTime startedAt, Exception e);
    }
}
