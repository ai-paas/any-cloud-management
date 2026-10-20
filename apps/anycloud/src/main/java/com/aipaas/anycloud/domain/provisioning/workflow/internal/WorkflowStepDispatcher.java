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
 * 단계 실행을 소비자 스레드에서 분리.
 *
 * <p>BOOTSTRAP 최악 예산 170분, AMQP ack 창은 그보다 짧음. 소비자 스레드에서 완료까지 실행하면
 * ack 창 초과 시점에 채널이 닫히고 메시지 재전달로 동일 작업이 처음부터 재실행.
 *
 * <p>이 지점에서는 소유권만 획득하고 즉시 반환. 메시지는 즉시 ack. 크래시로 유실된 실행은
 * {@code StuckWorkflowStepReaper} 가 재투입.
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
     * 가드와 소유권은 동기 확인. 비동기 처리 시 두 메시지 동시 제출 가능.
     *
     * @param onFailure 실패 기록. 호출부 맥락으로 기록
     */
    public void dispatch(VmClusterWorkflowMessage message, Runnable work, FailureRecorder onFailure) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        // 삭제는 앞 단계 뒤에 대기하지 않음. 사용자의 복구 수단이라 수 시간 대기 금지.
        boolean acquired = message.getStep() == VmClusterWorkflowStep.DESTROY
                ? processingLock.preempt(message.getVmClusterId(), message.getMessageId())
                : processingLock.acquire(message.getVmClusterId(), message.getMessageId());
        if (!acquired) {
            // 폐기 시 ack 되어 소실. 앞 단계가 락을 보유한 채 다음 단계를 발행하므로 이 지점은
            // 항상 충돌하고 워크플로 정지. 재큐하면 잠시 후 재도착.
            throw new WorkflowStepBusyException(message.getClusterName(), message.getStep());
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            executor.execute(() -> run(message, work, onFailure, startedAt));
        } catch (RejectedExecutionException e) {
            // 큐 포화 시 획득한 락을 해제하고 메시지 재큐. 예외를 묻으면 해당 단계 미실행.
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
