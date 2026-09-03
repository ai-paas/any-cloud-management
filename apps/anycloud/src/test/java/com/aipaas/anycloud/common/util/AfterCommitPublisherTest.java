package com.aipaas.anycloud.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * H2 — AfterCommitPublisher 의 핵심 거동 회귀 방지.
 * <ul>
 *   <li>활성 TX 가 없으면 즉시 실행 (fallback).</li>
 *   <li>활성 TX 가 있으면 commit 이후에만 실행, rollback (status != COMMITTED) 시 실행 안 함.</li>
 *   <li>action 이 예외 던져도 publish 자체는 throw 하지 않음 — 비즈니스 흐름 보호.</li>
 * </ul>
 */
class AfterCommitPublisherTest extends AbstractUnitTest {

    private final AfterCommitPublisher publisher = new AfterCommitPublisher();

    @AfterEach
    void cleanup() {
        // 테스트가 sync 를 시작했으면 닫아준다 (다른 테스트로 누수 방지).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publish_noActiveTx_runsImmediately() {
        AtomicInteger counter = new AtomicInteger();
        publisher.publish("test", counter::incrementAndGet);
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void publish_activeTx_runsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger counter = new AtomicInteger();

        publisher.publish("test", counter::incrementAndGet);
        assertThat(counter.get()).as("등록만 됐을 뿐 아직 실행 안 됨").isZero();

        // commit 신호 시뮬레이션.
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void publish_activeTxRollback_doesNotRun() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger counter = new AtomicInteger();

        publisher.publish("test", counter::incrementAndGet);

        // rollback path — afterCommit 호출되지 않고 afterCompletion(ROLLED_BACK) 만.
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertThat(counter.get()).as("rollback 시 publish 실행 안 됨").isZero();
    }

    @Test
    void publish_actionThrows_isSwallowed() {
        // 활성 TX 없이 즉시 실행 path 에서 action 이 던져도 caller 에 전파 안 됨.
        publisher.publish("test", () -> {
            throw new RuntimeException("intentional");
        });
        // 여기 도달했다는 자체가 검증 — assertion 불필요.
    }

    @Test
    void publishWithPayload_passesPayloadToConsumer() {
        StringBuilder captured = new StringBuilder();
        publisher.publish("test", "hello", captured::append);
        assertThat(captured.toString()).isEqualTo("hello");
    }
}
