package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.model.AddonWorkflowMessage;
import com.aipaas.anycloud.domain.addon.properties.AddonWorkflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** RabbitMQ publisher — addon install/uninstall enqueue. */
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
        afterCommit(() -> {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getInstallRoutingKey(), msg);
            log.info(
                    "AddonInstallPublisher: enqueue install cluster={} addon={} op={}",
                    clusterId,
                    addonId,
                    operationId);
        });
    }

    /** Uninstall queue 로 publish — DELETING state 의 addon row. */
    public void enqueueUninstall(String clusterId, String addonId, String operationId) {
        AddonWorkflowMessage msg = new AddonWorkflowMessage(clusterId, addonId, operationId, MDC.get("requestId"));
        afterCommit(() -> {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getUninstallRoutingKey(), msg);
            log.info(
                    "AddonInstallPublisher: enqueue uninstall cluster={} addon={} op={}",
                    clusterId,
                    addonId,
                    operationId);
        });
    }

    /**
     * 커밋이 끝난 뒤에 보낸다.
     *
     * <p>트랜잭션 안에서 보내면 소비자가 아직 없는 row 를 찾아 "addon row not found — drop" 으로
     * 버린다. 메시지가 사라져 addon 이 ENQUEUED 에서 영영 움직이지 않는다.
     */
    private void afterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }
}
