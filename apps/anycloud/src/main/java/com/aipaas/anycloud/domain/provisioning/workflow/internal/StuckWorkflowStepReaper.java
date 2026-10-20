package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.StuckStepRules;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 중 상태로 정지한 단계 재실행.
 *
 * <p>메시지 즉시 ack 구조라 크래시 시 RabbitMQ 재전달 없음. 이 루프가 없으면 해당 단계는 정지
 * 상태로 잔존.
 *
 * <p>재전송 대상은 <b>메시지</b>이며 인프라는 대상 외. 단계 서비스가 멱등이라 완료된 작업의
 * 재실행 결과는 동일.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckWorkflowStepReaper {

    private static final List<VmClusterStatus> IN_FLIGHT = List.of(
            VmClusterStatus.PROVISIONING,
            VmClusterStatus.BOOTSTRAPPING,
            VmClusterStatus.VERIFYING,
            VmClusterStatus.DELETING);

    private final VmClusterRepository vmClusterRepository;
    private final VmClusterWorkflowPublisher publisher;

    @Value("${anycloud.vm-cluster.workflow.redrive.enabled:true}")
    private boolean enabled;

    /** 단계 최악 예산보다 커야 함. 작으면 실행 중인 작업이 중복 실행. */
    @Value("${anycloud.vm-cluster.workflow.processing-stale-after:PT180M}")
    private Duration staleAfter;

    @Scheduled(cron = "${anycloud.vm-cluster.workflow.redrive.cron:0 */5 * * * *}")
    @SchedulerLock(name = "stuckWorkflowStepRedrive", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional(readOnly = true)
    public void redriveStuckSteps() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (VmClusterEntity cluster : vmClusterRepository.findByProvisioningStatusIn(IN_FLIGHT)) {
            if (!StuckStepRules.needsRedrive(cluster, now, staleAfter)) {
                continue;
            }
            VmClusterWorkflowStep step = StuckStepRules.stepFor(cluster.getProvisioningStatus());
            log.warn(
                    "멈춘 단계를 다시 민다 cluster={} step={} 시작={}",
                    cluster.getClusterName(),
                    step,
                    cluster.getProcessingStartedAt());
            publish(cluster, step);
        }
    }

    private void publish(VmClusterEntity cluster, VmClusterWorkflowStep step) {
        // 신규 messageId 로 재투입. 동일 id 사용 시 처리 완료 가드에 걸려 미실행.
        VmClusterWorkflowMessage message = VmClusterWorkflowMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .vmClusterId(cluster.getId())
                .clusterName(cluster.getClusterName())
                .step(step)
                .build();
        switch (step) {
            case PROVISION -> publisher.publishProvision(message);
            case BOOTSTRAP -> publisher.publishBootstrap(message);
            case VERIFY -> publisher.publishVerify(message);
            case DESTROY -> publisher.publishDestroy(message);
        }
    }
}
