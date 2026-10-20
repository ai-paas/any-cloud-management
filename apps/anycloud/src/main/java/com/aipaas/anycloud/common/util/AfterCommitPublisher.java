package com.aipaas.anycloud.common.util;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RabbitMQ, webhook, SSE 등 외부 발행을 트랜잭션 commit 이후로 지연. rollback 시 발행도
 * 미실행이라 DB 와 메시지 상태 일치.
 *
 * <p>트랜잭션 부재 시 즉시 발행.
 *
 * <p>commit 과 발행 사이 프로세스 종료 시 메시지 유실. 이 구간까지 보장하려면 DB outbox
 * 테이블 필요.
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
            // @Async, scheduled 등 트랜잭션 없는 호출. 대기할 commit 이 없어 즉시 발행.
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

    /** action(payload) 형식 helper: payload 가 명시적인 경우. */
    public <T> void publish(String description, T payload, Consumer<T> action) {
        publish(description, () -> action.accept(payload));
    }

    private void runSafely(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 비즈니스 흐름은 commit 완료 상태라 예외 전파해도 롤백 대상 없음. 알림은 로그로 처리.
            log.warn("afterCommit publish failed ({}): {}", description, e.toString(), e);
        }
    }
}
