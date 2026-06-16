package com.aipaas.anycloud.domain.audit.internal;

import com.aipaas.anycloud.domain.agent.IdempotencyRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 IdempotencyRecord 정기 삭제. IdempotencyFilter 는 lazy-delete (요청 시점에 만료된 row 발견 시 삭제)
 * 만 수행하므로, 호출이 뜸한 키는 영구 잔존하여 row 누적. 본 scheduler 가 1 시간마다 전체 cleanup.
 * <p>
 * 메트릭 {@code anycloud.idempotency.cleanup{result}} 로 삭제 수를 노출.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyRecordCleanupScheduler {

    private final IdempotencyRecordRepository repository;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void init() {
        log.info("IdempotencyRecord cleanup scheduler ENABLED — cron: hourly");
    }

    @Scheduled(cron = "${anycloud.idempotency.cleanup.cron:0 0 * * * *}") // 매시 00분
    @SchedulerLock(name = "idempotencyCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        try {
            int removed = repository.deleteExpired(LocalDateTime.now());
            if (removed > 0) {
                log.info("IdempotencyRecord cleanup: {} expired rows removed", removed);
                Counter.builder("anycloud.idempotency.cleanup")
                        .tags(Tags.of("result", "removed"))
                        .register(meterRegistry)
                        .increment(removed);
            } else {
                log.debug("IdempotencyRecord cleanup: no expired rows");
            }
        } catch (Exception e) {
            log.error("IdempotencyRecord cleanup failed: {}", e.toString());
            Counter.builder("anycloud.idempotency.cleanup")
                    .tags(Tags.of("result", "error"))
                    .register(meterRegistry)
                    .increment();
        }
    }
}
