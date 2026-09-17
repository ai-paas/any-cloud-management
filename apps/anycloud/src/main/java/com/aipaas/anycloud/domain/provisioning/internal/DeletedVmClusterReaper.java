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
 * 오래된 삭제 이력을 걷어낸다.
 *
 * <p>삭제는 감사, 비용 정산, 원인 분석을 위해 행을 남긴다. 그런데 정리하는 곳이 없어 영원히
 * 쌓였다 — operation, audit log, state history 에는 보관 기한이 있는데 vm_cluster 에만 없었다.
 *
 * <p>삭제 시점에 requestConfig, rawOutputs, bootstrapLog 는 이미 비워지므로 남는 것은
 * 메타데이터뿐이다. 그래도 목록 조회가 매번 전체를 훑는 구조라 누적되면 같이 무거워진다.
 */
@Slf4j
@Component
public class DeletedVmClusterReaper {

    private final VmClusterRepository vmClusterRepository;
    private final int retentionDays;

    public DeletedVmClusterReaper(
            VmClusterRepository vmClusterRepository,
            // 다른 정리 잡과 같은 규약 — retention-days(int). 혼자 Duration 을 쓰면 운영자가 헷갈린다.
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
        // 0 이하는 "정리하지 않음" 이다. 실수로 전체를 지우면 복구할 수 없다.
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
            // 정리가 실패해도 스케줄러 스레드가 죽으면 안 된다.
            log.warn("삭제 이력 정리 실패: {}", e.toString());
        }
    }
}
