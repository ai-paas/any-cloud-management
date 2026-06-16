package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.model.AddonWorkflowMessage;
import com.aipaas.anycloud.domain.addon.properties.AddonWorkflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ publisher — addon install/uninstall enqueue.
 *
 * <p>caller (REST API, ClusterStatusChangedEvent listener, retry endpoint) 가 본 publisher 호출
 * → broker 에 message 적재 → {@code RabbitMqAddonInstallListener} 가 consume.
 *
 * <p>publish 자체는 사실상 trivial — 본 클래스는 logging/MDC/metric 통합 + caller 가 RabbitTemplate
 * 의존 안 갖도록 캡슐화.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "addon-workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AddonInstallPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AddonWorkflowProperties properties;

    /** Install queue 로 publish. addonId 는 ClusterAddonEntity.id, operationId 는 LRO row id. */
    public void enqueueInstall(String clusterId, String addonId, String operationId) {
        AddonWorkflowMessage msg = new AddonWorkflowMessage(clusterId, addonId, operationId, MDC.get("requestId"));
        rabbitTemplate.convertAndSend(properties.getExchange(), properties.getInstallRoutingKey(), msg);
        log.info("AddonInstallPublisher: enqueue install cluster={} addon={} op={}", clusterId, addonId, operationId);
    }

    /** Uninstall queue 로 publish — DELETING state 의 addon row. */
    public void enqueueUninstall(String clusterId, String addonId, String operationId) {
        AddonWorkflowMessage msg = new AddonWorkflowMessage(clusterId, addonId, operationId, MDC.get("requestId"));
        rabbitTemplate.convertAndSend(properties.getExchange(), properties.getUninstallRoutingKey(), msg);
        log.info("AddonInstallPublisher: enqueue uninstall cluster={} addon={} op={}", clusterId, addonId, operationId);
    }
}
