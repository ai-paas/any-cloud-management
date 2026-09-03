package com.aipaas.anycloud.domain.operation.internal;

import com.aipaas.anycloud.domain.operation.OperationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operation 테이블 위생 sweeper — 완료된 (SUCCEEDED/FAILED/CANCELLED) 오래된 operation 행을 정기 삭제.
 *
 * <p>운영 환경에서 cluster 수 × 작업 빈도 → operation row 수가 무한 증가. UI 의 operation 이력 조회도
 * 점진 느려짐. 보통 30 일 정도 보관이면 충분 (사용자가 그 이상 거슬러 올라가지 않음).
 *
 * <p>설정:
 * <ul>
 *   <li>{@code anycloud.operation.cleanup.enabled} (default true) — toggle</li>
 *   <li>{@code anycloud.operation.cleanup.retention-days} (default 30) — 보관 기간</li>
 *   <li>{@code anycloud.operation.cleanup.cron} (default "0 30 3 * * *") — 매일 03:30 KST</li>
 * </ul>
 *
 * <p>ShedLock 으로 multi-replica 충돌 방지. RUNNING / PENDING 상태는 절대 안 지움 (active operation 보호).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationCleanupSweeper {

    private final OperationRepository operationRepository;

    @Value("${anycloud.operation.cleanup.enabled:true}")
    private boolean enabled;

    @Value("${anycloud.operation.cleanup.retention-days:30}")
    private int retentionDays;

    /**
     * 매일 03:30 (system tz) sweep. 운영자가 cron 변경 가능.
     *
     * <p>ShedLock — 같은 cluster 안의 backend 가 multi-replica 여도 한 노드에서만 실행.
     * lockAtMostFor 1h (RDB throttle 안전), lockAtLeastFor 5m (race-resistant).
     */
    @Scheduled(cron = "${anycloud.operation.cleanup.cron:0 30 3 * * *}")
    @SchedulerLock(name = "operationCleanupSweep", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    @Transactional
    public void sweep() {
        if (!enabled) {
            log.debug("Operation cleanup sweep disabled");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = operationRepository.deleteCompletedBefore(cutoff);
            log.info(
                    "Operation cleanup sweep done: deleted={} cutoff={} retention_days={}",
                    deleted,
                    cutoff,
                    retentionDays);
        } catch (Exception e) {
            // 본 sweep 실패가 다른 scheduler 영향 X — log 만.
            log.warn("Operation cleanup sweep failed: {}", e.toString());
        }
    }
}
