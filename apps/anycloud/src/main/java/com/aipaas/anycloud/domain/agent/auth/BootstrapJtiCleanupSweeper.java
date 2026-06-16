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
 * bootstrap_jti_used 테이블 위생 sweeper — 만료된 jti 정기 삭제. Redis 의 자동 TTL 대체.
 *
 * <p>jti 가 만료되면 안전하게 삭제 가능 (JWT 자체 만료 검증이 1차 방어선). 본 테이블 행 수는
 * 분당 발급 토큰 수 × 보관 기간 ≈ 0~100 수준. 매우 가볍지만 무한 증가는 막아야.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapJtiCleanupSweeper {

    private final BootstrapJtiRepository repository;

    @Value("${anycloud.bootstrap-jti.cleanup.enabled:true}")
    private boolean enabled;

    /**
     * 매일 04:00 (system tz) — operation cleanup (03:30) 직후 timing 차이.
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
