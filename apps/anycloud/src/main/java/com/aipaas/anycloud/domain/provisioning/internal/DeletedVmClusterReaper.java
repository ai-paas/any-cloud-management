package com.aipaas.anycloud.domain.provisioning.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관 기한이 지난 삭제 이력 제거.
 *
 * <p>삭제 시 감사, 비용 정산, 원인 분석용 행을 기록. 정리 주체가 없어 무한 누적.
 * operation, audit log, state history 에는 보관 기한이 있으나 vm_cluster 에만 부재.
 *
 * <p>삭제 시점에 requestConfig, rawOutputs, bootstrapLog 는 비워지므로 잔존 항목은
 * 메타데이터뿐. 목록 조회가 매번 전체를 순회하는 구조라 누적 시 조회 비용 증가.
 */
@Slf4j
@Component
public class DeletedVmClusterReaper {

    private final VmClusterRepository vmClusterRepository;
    private final int retentionDays;

    public DeletedVmClusterReaper(
            VmClusterRepository vmClusterRepository,
            // 다른 정리 잡과 동일 규약 retention-days(int). 단독 Duration 사용 시 운영자 혼동.
            @Value("${anycloud.vm-cluster.deleted.retention-days:90}") int retentionDays) {
        this.vmClusterRepository = vmClusterRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            cron = "${anycloud.vm-cluster.deleted.cleanup.cron:0 30 4 * * *}",
            zone = "${anycloud.vm-cluster.deleted.cleanup.zone:UTC}")
    @SchedulerLock(name = "deletedVmClusterReaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    @Transactional
    public void reap() {
        // 0 이하는 정리 비활성. 전체 삭제 시 복구 불가.
        if (retentionDays <= 0) {
            return;
        }
        try {
            int removed =
                    vmClusterRepository.deleteDeletedBefore(LocalDateTime.now().minusDays(retentionDays));
            if (removed > 0) {
                log.info("보관 기한이 지난 삭제 이력 {}건 정리 (기한 {}일)", removed, retentionDays);
            }
        } catch (Exception e) {
            // 정리 실패로 스케줄러 스레드 종료 금지.
            log.warn("삭제 이력 정리 실패: {}", e.toString());
        }
    }
}
