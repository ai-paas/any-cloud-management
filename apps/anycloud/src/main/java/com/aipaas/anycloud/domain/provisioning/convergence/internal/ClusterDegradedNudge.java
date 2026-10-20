package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterConvergenceOrchestrator;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * DEGRADED 직후 수렴 1회 추가 확인.
 *
 * <p>VERIFY 가 수렴 예산을 소진하면 DEGRADED 로 전이하고 이후 정기 조정 주기(기본 5분) 전체를
 * 대기. 실측 DEGRADED 에서 READY 까지 9분 22초, 대부분이 대기 시간. 애드온 기동 시점이라 이
 * 지점의 짧은 재확인이 비용 대비 효과가 큼.
 *
 * <p>확인은 스케줄러 스레드에서 실행. VERIFY 는 RabbitMQ consumer 스레드 위에 있어 이 지점의
 * 추가 대기는 consumer 하나를 점유.
 */
@Slf4j
@Component
public class ClusterDegradedNudge {

    private final ClusterConvergenceOrchestrator orchestrator;
    private final VmClusterRepository vmClusterRepository;
    private final TaskScheduler taskScheduler;
    private final Duration delay;

    public ClusterDegradedNudge(
            ClusterConvergenceOrchestrator orchestrator,
            VmClusterRepository vmClusterRepository,
            TaskScheduler taskScheduler,
            @Value("${anycloud.vm-cluster.convergence.degraded-recheck-delay:PT30S}") Duration delay) {
        this.orchestrator = orchestrator;
        this.vmClusterRepository = vmClusterRepository;
        this.taskScheduler = taskScheduler;
        this.delay = delay;
    }

    public void onDegraded(String clusterName) {
        taskScheduler.schedule(() -> recheck(clusterName), Instant.now().plus(delay));
    }

    private void recheck(String clusterName) {
        try {
            // 확인 대기 중 사용자 삭제 가능. 복원 금지.
            boolean stillDegraded = vmClusterRepository
                    .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                    .map(c -> c.getProvisioningStatus() == VmClusterStatus.DEGRADED)
                    .orElse(false);
            if (!stillDegraded) {
                return;
            }
            orchestrator.driveOne(clusterName);
        } catch (Exception e) {
            // 보조 기능. 실패 시 정기 조정이 처리.
            log.warn("DEGRADED 재확인 실패 cluster={}: {}", clusterName, e.toString());
        }
    }
}
