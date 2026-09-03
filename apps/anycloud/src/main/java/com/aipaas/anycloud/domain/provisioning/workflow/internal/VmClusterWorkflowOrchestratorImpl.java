package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowOrchestrator;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageGuard;
import com.aipaas.anycloud.domain.provisioning.workflow.WorkflowMessageLogService;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterBootstrapStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterDestroyStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterProvisionStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterVerifyStepService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterWorkflowOrchestratorImpl implements VmClusterWorkflowOrchestrator {

    private final VmClusterProvisionStepService provisionStepService;
    private final VmClusterBootstrapStepService bootstrapStepService;
    private final VmClusterVerifyStepService verifyStepService;
    private final VmClusterDestroyStepService destroyStepService;
    private final WorkflowMessageGuard messageGuard;
    private final WorkflowMessageLogService workflowMessageLogService;

    @Override
    // Provision only creates infrastructure and persists outputs for the next workflow stages.
    public void provisionInfrastructure(VmClusterWorkflowMessage message) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            provisionStepService.execute(
                    message.getVmClusterId(), message.getClusterName(), message.getProvisioningRequest());
            workflowMessageLogService.recordProcessed(message, startedAt);
        } catch (Exception e) {
            recordFailureAndSwallow(message, startedAt, e);
        } finally {
            messageGuard.markProcessed(message);
        }
    }

    @Override
    // Bootstrap assumes nodes are already reachable and prepares the Kubernetes control plane and workers.
    public void bootstrapCluster(VmClusterWorkflowMessage message) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            bootstrapStepService.execute(message.getVmClusterId(), message.getClusterName());
            workflowMessageLogService.recordProcessed(message, startedAt);
        } catch (Exception e) {
            recordFailureAndSwallow(message, startedAt, e);
        } finally {
            messageGuard.markProcessed(message);
        }
    }

    @Override
    // Verify is the last gate before registration; it should only check readiness, not mutate infra.
    public void verifyCluster(VmClusterWorkflowMessage message) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            verifyStepService.execute(message.getVmClusterId(), message.getClusterName());
            workflowMessageLogService.recordProcessed(message, startedAt);
        } catch (Exception e) {
            recordFailureAndSwallow(message, startedAt, e);
        } finally {
            messageGuard.markProcessed(message);
        }
    }

    @Override
    // Destroy runs independently from create workflow and must tolerate partially created resources.
    public void destroyCluster(VmClusterWorkflowMessage message) {
        if (!messageGuard.shouldProcess(message)) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            destroyStepService.execute(message.getClusterName());
            workflowMessageLogService.recordProcessed(message, startedAt);
        } catch (Exception e) {
            recordFailureAndSwallow(message, startedAt, e);
        } finally {
            messageGuard.markProcessed(message);
        }
    }

    /**
     * 실패를 workflow_message_log 에 FAILED 로 기록하고 예외는 삼킨다.
     * <p>
     * Step service 가 자체 try-catch 에서 entity 상태(failedAt/lastError 등)를 이미 저장했고,
     * markProcessed 가 finally 에서 호출되어 동일 messageId 의 재전달을 차단하므로
     * RabbitMQ 로 nack 할 불필요. 즉 워크플로우 실패는 entity 와 log 양쪽에 영속되지만
     * 메시지 처리는 정상 종료되어 다음 메시지가 흘러간다.
     */
    private void recordFailureAndSwallow(VmClusterWorkflowMessage message, LocalDateTime startedAt, Throwable e) {
        log.error(
                "Workflow step {} failed for cluster {} (messageId={}): {}",
                message.getStep(),
                message.getClusterName(),
                message.getMessageId(),
                e.toString());
        workflowMessageLogService.recordFailed(message, startedAt, e);
    }
}
