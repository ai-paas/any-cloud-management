package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterConvergenceOrchestrator;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentObservation;
import com.aipaas.anycloud.domain.provisioning.convergence.ConvergenceSignal;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * READY / DEGRADED 클러스터의 컴포넌트 상태를 주기적으로 조정.
 *
 * <p>VERIFY 단계의 시간 제한 수렴 루프가 끝내지 못한 일을 이어받는다. consumer 스레드를 오래
 * 점유하지 않으려면 긴 대기는 여기로 와야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterConvergenceOrchestratorImpl implements ClusterConvergenceOrchestrator {

    private static final List<VmClusterStatus> TARGET_STATUSES =
            List.of(VmClusterStatus.READY, VmClusterStatus.DEGRADED);

    private final ClusterComponentObserver observer;
    private final RequestedAddonInspector addonInspector;
    private final VmClusterRepository vmClusterRepository;

    @Override
    @Scheduled(
            fixedDelayString = "${anycloud.vm-cluster.convergence.interval-ms:300000}",
            initialDelayString = "${anycloud.vm-cluster.convergence.initial-delay-ms:60000}")
    @SchedulerLock(name = "vmClusterConvergence", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    @Transactional
    public void drive() {
        for (VmClusterEntity vmCluster : vmClusterRepository.findByProvisioningStatusIn(TARGET_STATUSES)) {
            try {
                driveOne(vmCluster);
            } catch (Exception e) {
                // 한 클러스터의 실패가 나머지 조정을 멈추면 안 된다.
                log.warn("컴포넌트 조정 실패 cluster={}: {}", vmCluster.getClusterName(), e.toString());
            }
        }
    }

    private void driveOne(VmClusterEntity vmCluster) {
        ConvergenceVerdict verdict = evaluate(collectSignals(observer, addonInspector, vmCluster));
        VmClusterStatus current = vmCluster.getProvisioningStatus();
        VmClusterStatus next =
                switch (verdict) {
                    case SATISFIED -> VmClusterStatus.READY;
                    case UNSATISFIED -> VmClusterStatus.DEGRADED;
                    case INCONCLUSIVE -> current;
                };
        if (next == current) {
            return;
        }
        // raw setter 가 아니라 transitionTo — 상태 이력과 메트릭이 여기에 달려 있다.
        vmCluster.transitionTo(next, "convergence.reconcile");
        vmClusterRepository.save(vmCluster);
        log.info("컴포넌트 조정으로 상태 변경 cluster={} {} -> {}", vmCluster.getClusterName(), current, next);
    }

    /** 구성 요소 관측과 요청 addon 상태를 한 묶음으로. 어느 쪽 실패든 같은 무게로 본다. */
    static List<ConvergenceSignal> collectSignals(
            ClusterComponentObserver observer, RequestedAddonInspector inspector, VmClusterEntity vmCluster) {
        List<ConvergenceSignal> signals = new java.util.ArrayList<>(
                observer.observe(vmCluster).stream().map(ComponentObservation::toSignal).toList());
        signals.addAll(inspector.inspect(vmCluster));
        return signals;
    }

    /** UNSATISFIED 가 INCONCLUSIVE 를 이긴다 — 하나라도 확실히 미충족이면 판정은 미충족이다. */
    static ConvergenceVerdict evaluate(List<ConvergenceSignal> signals) {
        boolean anyUnknown = false;
        for (ConvergenceSignal signal : signals) {
            if (signal.requirement() != Requirement.REQUIRED) {
                continue;
            }
            if (signal.health() == ComponentHealth.NOT_READY) {
                return ConvergenceVerdict.UNSATISFIED;
            }
            if (signal.health() == ComponentHealth.UNKNOWN) {
                anyUnknown = true;
            }
        }
        return anyUnknown ? ConvergenceVerdict.INCONCLUSIVE : ConvergenceVerdict.SATISFIED;
    }
}
