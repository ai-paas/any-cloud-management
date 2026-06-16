package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterWorkflowQueueResponse;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterWorkflowProperties;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowQueueService;
import java.util.List;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.stereotype.Service;

// @ConditionalOnBean(AmqpAdmin.class) 제거— 일반 @Service 에서는 조건 평가 시점에
// auto-config 의 AmqpAdmin 이 아직 미등록일 수 있어 false 판정 → bean 미생성 → controller 503.
// AmqpAdmin 은 spring-boot-starter-amqp classpath 존재 시 항상 생성되므로 (연결 실패는 호출
// 시점 예외) 직접 주입이 안전.
@Service
@RequiredArgsConstructor
public class VmClusterWorkflowQueueServiceImpl implements VmClusterWorkflowQueueService {

    private static final String MESSAGE_COUNT = "QUEUE_MESSAGE_COUNT";
    private static final String CONSUMER_COUNT = "QUEUE_CONSUMER_COUNT";

    private final VmClusterWorkflowProperties properties;
    private final AmqpAdmin amqpAdmin;

    @Override
    public List<VmClusterWorkflowQueueResponse> getWorkflowQueues() {
        return List.of(
                toPrimaryQueueResponse(properties.getProvisionQueue(), properties.getProvisionRoutingKey()),
                toPrimaryQueueResponse(properties.getBootstrapQueue(), properties.getBootstrapRoutingKey()),
                toPrimaryQueueResponse(properties.getVerifyQueue(), properties.getVerifyRoutingKey()),
                toPrimaryQueueResponse(properties.getDestroyQueue(), properties.getDestroyRoutingKey()),
                toDeadLetterQueueResponse(properties.getDeadLetterQueue(), properties.getDeadLetterRoutingKey()));
    }

    private VmClusterWorkflowQueueResponse toPrimaryQueueResponse(String queueName, String routingKey) {
        Properties queueProperties = amqpAdmin.getQueueProperties(queueName);
        Integer messageCount = queueProperties == null ? 0 : asInteger(queueProperties.get(MESSAGE_COUNT));
        Integer consumerCount = queueProperties == null ? 0 : asInteger(queueProperties.get(CONSUMER_COUNT));

        return VmClusterWorkflowQueueResponse.builder()
                .workflowEnabled(properties.isEnabled())
                .queueName(queueName)
                .queueType("PRIMARY")
                .routingKey(routingKey)
                .deadLetterEnabled(true)
                .messageCount(messageCount)
                .consumerCount(consumerCount)
                .build();
    }

    private VmClusterWorkflowQueueResponse toDeadLetterQueueResponse(String queueName, String routingKey) {
        Properties queueProperties = amqpAdmin.getQueueProperties(queueName);
        Integer messageCount = queueProperties == null ? 0 : asInteger(queueProperties.get(MESSAGE_COUNT));
        Integer consumerCount = queueProperties == null ? 0 : asInteger(queueProperties.get(CONSUMER_COUNT));

        return VmClusterWorkflowQueueResponse.builder()
                .workflowEnabled(properties.isEnabled())
                .queueName(queueName)
                .queueType("DEAD_LETTER")
                .routingKey(routingKey)
                .deadLetterEnabled(false)
                .messageCount(messageCount)
                .consumerCount(consumerCount)
                .build();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
