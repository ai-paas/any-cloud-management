package com.aipaas.anycloud.common.util;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RabbitMQ, webhook, SSE 같은 외부 발행을 트랜잭션 commit 이후로 미룹니다. rollback 되면 발행도
 * 일어나지 않아 DB 와 메시지가 어긋나지 않습니다.
 *
 * <p>트랜잭션이 없으면 그 자리에서 발행합니다.
 *
 * <p>commit 과 발행 사이에 프로세스가 죽으면 메시지는 유실됩니다. 그것까지 막으려면 DB outbox
 * 테이블이 필요합니다.
 */
@Slf4j
@Component
public class AfterCommitPublisher {

    /**
     * action 을 현재 트랜잭션 commit 이후에 실행. 활성 TX 가 없으면 즉시 실행.
     *
     * @param description 로그 / 메트릭에 사용할 짧은 설명 (예: "rabbit.publishProvision")
     * @param action      commit 후 실행할 작업
     */
    public void publish(String description, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // @Async 나 scheduled 처럼 트랜잭션이 없는 호출. 미룰 commit 이 없으니 바로 발행합니다.
            runSafely(description, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(description, action);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    log.debug("Skipping {} (TX status={})", description, status);
                }
            }
        });
    }

    /** action(payload) 형식 helper — payload 가 명시적인 경우. */
    public <T> void publish(String description, T payload, Consumer<T> action) {
        publish(description, () -> action.accept(payload));
    }

    private void runSafely(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 비즈니스 흐름은 이미 commit 됐으므로 여기서 던지면 되돌릴 게 없습니다.
            // 알림은 로그로 넘깁니다.
            log.warn("afterCommit publish failed ({}): {}", description, e.toString(), e);
        }
    }
}
