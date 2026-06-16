package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowOrchestrator;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("vm-cluster-worker")
@ConditionalOnProperty(prefix = "vm-cluster-workflow", name = "worker-enabled", havingValue = "true")
public class RabbitMqVmClusterWorkerListener {

    private final VmClusterWorkflowOrchestrator vmClusterWorkflowOrchestrator;

    @RabbitListener(queues = "${vm-cluster-workflow.bootstrap-queue}")
    public void onBootstrap(VmClusterWorkflowMessage message) {
        try (var ignored = LoggingMdc.scope(mdcOf(message))) {
            log.info("Worker received bootstrap workflow message");
            vmClusterWorkflowOrchestrator.bootstrapCluster(message);
        }
    }

    @RabbitListener(queues = "${vm-cluster-workflow.verify-queue}")
    public void onVerify(VmClusterWorkflowMessage message) {
        try (var ignored = LoggingMdc.scope(mdcOf(message))) {
            log.info("Worker received verify workflow message");
            vmClusterWorkflowOrchestrator.verifyCluster(message);
        }
    }

    private static Map<String, Object> mdcOf(VmClusterWorkflowMessage message) {
        Map<String, Object> m = new HashMap<>();
        m.put(LoggingMdc.CLUSTER_NAME, message.getClusterName());
        m.put(LoggingMdc.MESSAGE_ID, message.getMessageId());
        if (message.getStep() != null) {
            m.put(LoggingMdc.STEP, message.getStep().name());
        }
        m.put(LoggingMdc.PROVISIONING_ID, message.getVmClusterId());
        return m;
    }
}
