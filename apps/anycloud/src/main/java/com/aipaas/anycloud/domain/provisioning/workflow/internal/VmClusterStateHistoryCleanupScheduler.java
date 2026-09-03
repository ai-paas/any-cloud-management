package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRepository;
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
 * VmCluster state history retention scheduler.
 *
 * <p>매일 03:45 KST (audit log cleanup 03:30 직후) 에 retention window 보다 오래된 history row
 * 일괄 삭제. row 수 unbounded growth 방지 — fleet 규모 + 운영 시간 누적 시 GB 단위 증가 가능.
 *
 * <p>Default retention: 180일. {@code anycloud.vm-cluster.state-history.retention-days} 로 운영자
 * override 가능.
 *
 * <p>ShedLock 으로 multi-instance leader election. Metric:
 * {@code anycloud.vmcluster.state_history.cleanup{result="removed|none|error"}}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VmClusterStateHistoryCleanupScheduler {

    private final VmClusterStateHistoryRepository repository;
    private final MeterRegistry meterRegistry;

    @Value("${anycloud.vm-cluster.state-history.retention-days:180}")
    private int retentionDays;

    @PostConstruct
    void init() {
        log.info(
                "VmCluster state history cleanup scheduler ENABLED — retention={} days, cron: daily 03:45",
                retentionDays);
    }

    @Scheduled(cron = "${anycloud.vm-cluster.state-history.cleanup.cron:0 45 3 * * *}")
    @SchedulerLock(name = "vmClusterStateHistoryCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        log.info("VmCluster state history cleanup starting (cutoff={}, retention_days={})", cutoff, retentionDays);
        try {
            int removed = repository.deleteByCreatedAtBefore(cutoff);
            String tag = removed > 0 ? "removed" : "none";
            Counter.builder("anycloud.vmcluster.state_history.cleanup")
                    .tags(Tags.of("result", tag))
                    .description("VmCluster state history retention cleanup outcomes")
                    .register(meterRegistry)
                    .increment(Math.max(removed, 1));
            if (removed > 0) {
                log.info("VmCluster state history cleanup: {} rows removed (older than {})", removed, cutoff);
            } else {
                log.info("VmCluster state history cleanup: no expired rows (cutoff={})", cutoff);
            }
        } catch (Exception e) {
            Counter.builder("anycloud.vmcluster.state_history.cleanup")
                    .tags(Tags.of("result", "error"))
                    .register(meterRegistry)
                    .increment();
            log.error("VmCluster state history cleanup failed: {}", e.getMessage(), e);
        }
    }
}
