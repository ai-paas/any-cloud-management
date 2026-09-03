package com.aipaas.anycloud.common.util;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * H2 — outbox-lite. 트랜잭션 안에서 RabbitMQ / webhook / SSE 같은 외부 sink 로 발행해야 할 때,
 * 트랜잭션 commit 이후로 발행을 미뤄 두 시스템 간 inconsistency 를 줄인다.
 * <p>
 * 시나리오:
 * <ol>
 *   <li>호출 메서드 안: {@code repository.save(...)}</li>
 *   <li>호출 메서드 안: {@code afterCommitPublisher.publish(() -> rabbit.send(msg))}</li>
 *   <li>메서드 return 후 트랜잭션 commit</li>
 *   <li>commit 성공 시: rabbit.send 호출</li>
 *   <li>commit 실패 (rollback) 시: rabbit.send 호출되지 않음 → DB 변화 없음 + 메시지도 없음 = 일관</li>
 * </ol>
 * <p>
 * 트랜잭션이 없는 컨텍스트에서 호출되면 즉시 발행 (fallback). 진짜 outbox 가 필요한 시나리오
 * (commit 후 send 가 망가지면 message 유실) 는 DB outbox 테이블이 필요하나, 본 helper 는
 * 대부분의 use-case 에서 충분하고 추가 인프라 없이 즉시 사용 가능.
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
            // 트랜잭션 없음 → 즉시 발행. 호출자가 TX 컨텍스트 없는 케이스 (e.g. @Async / scheduled)
            // 는 outbox 시맨틱이 무의미하므로 기존 거동 유지.
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
            // publish 실패는 비즈니스 흐름이 이미 commit 됐으므로 throw 하지 않는다.
            // metric / alert 로 수렴되도록 warn log 남기고 swallow.
            log.warn("afterCommit publish failed ({}): {}", description, e.toString(), e);
        }
    }
}
