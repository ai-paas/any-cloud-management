package com.aipaas.anycloud.domain.addon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aipaas.anycloud.domain.addon.properties.AddonWorkflowProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 커밋 전 전송 시 소비자가 미생성 row 를 조회해 메시지 폐기.
 *
 * <p>폐기된 addon 은 ENQUEUED 에 고착되고 동일 이름 재설치도 불가.
 */
class AddonInstallPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AddonWorkflowProperties properties = new AddonWorkflowProperties();
    private final AddonInstallPublisher publisher = new AddonInstallPublisher(rabbitTemplate, properties);

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void nothingIsSentUntilTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.enqueueInstall("cluster", "addon-1", "op-1");

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void withoutATransactionItIsSentRightAway() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        publisher.enqueueUninstall("cluster", "addon-1", "op-1");

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
