package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowOrchestrator;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 단일 노드 fallback publisher. RabbitMQ 비활성화 시 사용.
 * <p>
 * 메시지에 {@code messageId} 가 비어 있으면 UUID 를 자동 할당한 뒤 비동기 dispatch.
 * RabbitMQ publisher 와 동일한 멱등성 키 보장으로 두 모드의 가드 동작을 일치시킨다.
 */
@Service
@ConditionalOnProperty(prefix = "vm-cluster-workflow", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalVmClusterWorkflowPublisherImpl implements VmClusterWorkflowPublisher {

    private final VmClusterWorkflowOrchestrator vmClusterWorkflowOrchestrator;
    private final TaskExecutor provisioningExecutor;

    public LocalVmClusterWorkflowPublisherImpl(
            @Lazy VmClusterWorkflowOrchestrator vmClusterWorkflowOrchestrator,
            @Qualifier(AsyncConfig.PROVISIONING_EXECUTOR) TaskExecutor provisioningExecutor) {
        this.vmClusterWorkflowOrchestrator = vmClusterWorkflowOrchestrator;
        this.provisioningExecutor = provisioningExecutor;
    }

    @Override
    public void publishProvision(VmClusterWorkflowMessage message) {
        ensureMessageId(message);
        CompletableFuture.runAsync(
                () -> vmClusterWorkflowOrchestrator.provisionInfrastructure(message), provisioningExecutor);
    }

    @Override
    public void publishBootstrap(VmClusterWorkflowMessage message) {
        ensureMessageId(message);
        CompletableFuture.runAsync(() -> vmClusterWorkflowOrchestrator.bootstrapCluster(message), provisioningExecutor);
    }

    @Override
    public void publishVerify(VmClusterWorkflowMessage message) {
        ensureMessageId(message);
        CompletableFuture.runAsync(() -> vmClusterWorkflowOrchestrator.verifyCluster(message), provisioningExecutor);
    }

    @Override
    public void publishDestroy(VmClusterWorkflowMessage message) {
        ensureMessageId(message);
        CompletableFuture.runAsync(() -> vmClusterWorkflowOrchestrator.destroyCluster(message), provisioningExecutor);
    }

    private void ensureMessageId(VmClusterWorkflowMessage message) {
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId(UUID.randomUUID().toString());
        }
    }
}
