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
 * DEGRADED 직후 한 번 더 수렴을 확인한다.
 *
 * <p>VERIFY 가 수렴 예산을 다 쓰면 DEGRADED 로 떨어지는데, 그 뒤 정기 조정 주기(기본 5분)를 통째로
 * 기다렸다. 실측에서 DEGRADED 에서 READY 까지 9분 22초가 걸렸고 대부분이 순수 대기였다. 애드온이
 * 막 뜨는 시점이라 여기서 짧게 한 번 더 보는 것이 가장 값싸게 시간을 줄인다.
 *
 * <p>확인은 스케줄러 스레드에서 돈다 — VERIFY 는 RabbitMQ consumer 스레드 위에 있어 여기서 더
 * 기다리면 consumer 하나가 통째로 묶인다.
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
            // 확인을 기다리는 사이 사용자가 삭제했을 수 있다. 되살리면 안 된다.
            boolean stillDegraded = vmClusterRepository
                    .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                    .map(c -> c.getProvisioningStatus() == VmClusterStatus.DEGRADED)
                    .orElse(false);
            if (!stillDegraded) {
                return;
            }
            orchestrator.driveOne(clusterName);
        } catch (Exception e) {
            // 부가 기능이다. 실패해도 정기 조정이 이어받는다.
            log.warn("DEGRADED 재확인 실패 cluster={}: {}", clusterName, e.toString());
        }
    }
}
