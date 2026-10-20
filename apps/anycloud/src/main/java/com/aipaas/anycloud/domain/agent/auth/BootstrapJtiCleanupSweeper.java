package com.aipaas.anycloud.domain.agent.auth;

import com.aipaas.anycloud.domain.agent.BootstrapJtiRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * bootstrap_jti_used 테이블 정리 sweeper. 만료 jti 정기 삭제로 Redis 자동 TTL 대체.
 *
 * <p>만료된 jti 는 삭제 가능. 1차 방어선은 JWT 자체 만료 검증. 행 수는 분당 발급 토큰 수와
 * 보관 기간의 곱이라 0~100 수준이나 미삭제 시 지속 누적.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapJtiCleanupSweeper {

    private final BootstrapJtiRepository repository;

    @Value("${anycloud.bootstrap-jti.cleanup.enabled:true}")
    private boolean enabled;

    /**
     * 매일 04:00 (system tz). operation cleanup (03:30) 직후 timing 차이.
     * ShedLock 으로 multi-replica 안전.
     */
    @Scheduled(cron = "${anycloud.bootstrap-jti.cleanup.cron:0 0 4 * * *}")
    @SchedulerLock(name = "bootstrapJtiCleanupSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    @Transactional
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int deleted = repository.deleteExpiredBefore(LocalDateTime.now());
            if (deleted > 0) {
                log.info("bootstrap_jti cleanup: deleted={}", deleted);
            }
        } catch (Exception e) {
            log.warn("bootstrap_jti cleanup failed: {}", e.toString());
        }
    }
}
