package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.WorkflowMessageLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크플로 메시지 처리 기록 정리.
 *
 * <p>메시지마다 한 행씩 쌓이는데 정리하는 곳이 없었다. 다른 도메인은 모두 보관 기간이 있다.
 *
 * <p>이 표는 멱등성에도 쓰인다 — {@code existsByMessageId} 가 재처리를 건너뛴다. 보관 기간이
 * 재전달 창(AMQP ack 타임아웃 30분)보다 짧으면 지워진 뒤 도착한 재전달이 같은 단계를 두 번 돌린다.
 *
 * <p>설정
 * <ul>
 *   <li>{@code anycloud.workflow-message-log.cleanup.enabled} (default true)</li>
 *   <li>{@code anycloud.workflow-message-log.retention-days} (default 30)</li>
 *   <li>{@code anycloud.workflow-message-log.cleanup.cron} (default 매일 04:15)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowMessageLogCleanupScheduler {

    private final WorkflowMessageLogRepository repository;

    @Value("${anycloud.workflow-message-log.cleanup.enabled:true}")
    private boolean enabled;

    @Value("${anycloud.workflow-message-log.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${anycloud.workflow-message-log.cleanup.cron:0 15 4 * * *}")
    @SchedulerLock(name = "workflowMessageLogCleanup", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    @Transactional
    public void sweep() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = repository.deleteCreatedBefore(cutoff);
            if (deleted > 0) {
                log.info("워크플로 메시지 기록 {}건 정리 (cutoff={}, 보관 {}일)", deleted, cutoff, retentionDays);
            }
        } catch (Exception e) {
            // 정리 실패가 워크플로를 막지 않는다.
            log.warn("워크플로 메시지 기록 정리 실패: {}", e.toString());
        }
    }
}
