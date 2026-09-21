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
 * 커밋 전에 보내면 소비자가 없는 row 를 찾아 메시지를 버린다.
 *
 * <p>버려진 addon 은 ENQUEUED 에서 움직이지 않고, 같은 이름으로 다시 설치할 수도 없다.
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
