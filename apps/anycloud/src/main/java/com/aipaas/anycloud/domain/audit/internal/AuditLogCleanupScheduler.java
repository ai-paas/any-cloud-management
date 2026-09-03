package com.aipaas.anycloud.domain.audit.internal;

import com.aipaas.anycloud.domain.audit.AuditLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit log retention scheduler — 일별로 retention window 보다 오래된 audit log row 일괄 삭제.
 * row 수 unbounded growth + DB 디스크 사용량 cap 유지.
 *
 * <p>Default retention: 90일. {@code anycloud.audit.retention-days} 로 운영자 override 가능.
 *
 * <p>Multi-instance 환경에선 {@code @SchedulerLock} 으로 leader 한 노드만 cleanup 실행 (ShedLock).
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code anycloud.audit.cleanup{result="removed|none|error", outcome=...}}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogCleanupScheduler {

    private final AuditLogRepository repository;
    private final MeterRegistry meterRegistry;

    @Value("${anycloud.audit.retention-days:90}")
    private int retentionDays;

    @PostConstruct
    void init() {
        log.info("AuditLog cleanup scheduler ENABLED — retention={} days, cron: daily 03:30", retentionDays);
    }

    /**
     * 매일 03:30 (KST) 에 실행 — 운영 외 시간대 부담 최소화. 큰 row 수 (수만~수십만) 삭제 시 lock
     * timeout (PT30M) 안에 충분. lockAtLeastFor=PT5M 으로 짧은 실패시 즉시 재시도 방지.
     */
    @Scheduled(cron = "${anycloud.audit.cleanup.cron:0 30 3 * * *}")
    @SchedulerLock(name = "auditLogCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        log.info("AuditLog cleanup starting (cutoff={}, retention_days={})", cutoff, retentionDays);
        try {
            int removed = repository.deleteByCreatedAtBefore(cutoff);
            String tag = removed > 0 ? "removed" : "none";
            Counter.builder("anycloud.audit.cleanup")
                    .tags(Tags.of("result", tag))
                    .description("Audit log retention cleanup outcomes")
                    .register(meterRegistry)
                    .increment(Math.max(removed, 1)); // result=none 일 때도 invocation 1 count
            if (removed > 0) {
                log.info("AuditLog cleanup: {} rows removed (older than {})", removed, cutoff);
            } else {
                log.info("AuditLog cleanup: no expired rows (cutoff={})", cutoff);
            }
        } catch (Exception e) {
            Counter.builder("anycloud.audit.cleanup")
                    .tags(Tags.of("result", "error"))
                    .register(meterRegistry)
                    .increment();
            log.error("AuditLog cleanup failed: {}", e.getMessage(), e);
            // 재시도 — 다음 cron 트리거에서 retry. 본 method 가 throw 하면 ShedLock 가 lock 빠르게 release
            // 안 함. silent failure 로 두고 운영자가 metric / log 로 detect.
        }
    }
}
