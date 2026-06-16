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
@Profile("!vm-cluster-worker")
@ConditionalOnProperty(
        prefix = "vm-cluster-workflow",
        name = "worker-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class RabbitMqVmClusterOrchestratorListener {

    private final VmClusterWorkflowOrchestrator vmClusterWorkflowOrchestrator;

    @RabbitListener(queues = "${vm-cluster-workflow.provision-queue}")
    public void onProvision(VmClusterWorkflowMessage message) {
        try (var ignored = LoggingMdc.scope(mdcOf(message))) {
            log.info("Received provision workflow message");
            vmClusterWorkflowOrchestrator.provisionInfrastructure(message);
        }
    }

    @RabbitListener(queues = "${vm-cluster-workflow.destroy-queue}")
    public void onDestroy(VmClusterWorkflowMessage message) {
        try (var ignored = LoggingMdc.scope(mdcOf(message))) {
            log.info("Received destroy workflow message");
            vmClusterWorkflowOrchestrator.destroyCluster(message);
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
