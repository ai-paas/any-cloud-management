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
 * 진행 중인 채로 멈춘 단계를 다시 민다.
 *
 * <p>메시지를 즉시 ack 하므로 크래시가 나면 되돌아올 것이 없다. 이 루프가 즉시 ack 의 안전망이고,
 * 없으면 재전달 루프를 메시지 유실로 바꾼 것에 지나지 않는다.
 *
 * <p>여기서 미는 것은 <b>메시지</b>지 인프라가 아니다. 단계 서비스가 멱등하므로 이미 끝난 일을
 * 다시 해도 결과는 같다.
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

    /** 단계의 최악 예산보다 길어야 한다. 짧으면 아직 도는 작업을 두 번 돌린다. */
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
        // 새 messageId 로 민다. 같은 id 로 밀면 처리 완료 가드가 걸러 아무 일도 일어나지 않는다.
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
