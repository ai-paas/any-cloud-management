package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.properties.VmClusterWorkflowProperties;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vm-cluster-workflow", name = "enabled", havingValue = "true")
public class RabbitMqVmClusterWorkflowPublisherImpl implements VmClusterWorkflowPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final VmClusterWorkflowProperties properties;

    @Override
    public void publishProvision(VmClusterWorkflowMessage message) {
        send(properties.getProvisionRoutingKey(), message);
    }

    @Override
    public void publishBootstrap(VmClusterWorkflowMessage message) {
        send(properties.getBootstrapRoutingKey(), message);
    }

    @Override
    public void publishVerify(VmClusterWorkflowMessage message) {
        send(properties.getVerifyRoutingKey(), message);
    }

    @Override
    public void publishDestroy(VmClusterWorkflowMessage message) {
        send(properties.getDestroyRoutingKey(), message);
    }

    private void send(String routingKey, VmClusterWorkflowMessage message) {
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId(UUID.randomUUID().toString());
        }
        rabbitTemplate.convertAndSend(properties.getExchange(), routingKey, message);
    }
}
