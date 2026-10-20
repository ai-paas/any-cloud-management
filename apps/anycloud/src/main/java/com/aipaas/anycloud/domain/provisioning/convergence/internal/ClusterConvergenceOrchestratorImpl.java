package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentRepairService;
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

/** READY / DEGRADED 클러스터의 컴포넌트 상태를 주기적으로 조정. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterConvergenceOrchestratorImpl implements ClusterConvergenceOrchestrator {

    private static final List<VmClusterStatus> TARGET_STATUSES =
            List.of(VmClusterStatus.READY, VmClusterStatus.DEGRADED);

    private final ClusterComponentObserver observer;
    private final RequestedAddonInspector addonInspector;
    private final VmClusterRepository vmClusterRepository;
    private final ClusterComponentRepairService repairService;
    private final com.aipaas.anycloud.domain.operation.OperationService operationService;

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
                // 클러스터 하나의 실패로 나머지 조정 중단 금지.
                log.warn("컴포넌트 조정 실패 cluster={}: {}", vmCluster.getClusterName(), e.toString());
            }
        }
    }

    @Override
    @Transactional
    public void driveOne(String clusterName) {
        vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .ifPresent(this::driveOne);
    }

    private void driveOne(VmClusterEntity vmCluster) {
        List<ConvergenceSignal> signals = collectSignals(observer, addonInspector, vmCluster);
        repairUnsatisfied(vmCluster, signals);
        ConvergenceVerdict verdict = evaluate(signals);
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
        // raw setter 대신 transitionTo 사용. 상태 이력과 메트릭이 이 경로에 연결.
        vmCluster.transitionTo(next, "convergence.reconcile");
        stampReadyAt(vmCluster, next);
        if (next == VmClusterStatus.READY) {
            // 이 경로로 READY 가 되면 VERIFY 가 연 operation 을 아무도 닫지 않아 RUNNING 으로 남았다.
            closeActiveOperation(vmCluster.getClusterName());
        }
        vmClusterRepository.save(vmCluster);
        log.info("컴포넌트 조정으로 상태 변경 cluster={} {} -> {}", vmCluster.getClusterName(), current, next);
    }

    /** 실패 시에도 상태 전이는 유효. 기록 정리 실패로 조정 중단 금지. */
    private void closeActiveOperation(String clusterName) {
        try {
            operationService
                    .findLatestActiveByResource("cluster", clusterName)
                    .ifPresent(op -> operationService.complete(op.getId(), "READY"));
        } catch (Exception e) {
            log.warn("작업 기록 종료 실패 cluster={}: {}", clusterName, e.toString());
        }
    }

    /**
     * READY 최초 도달 시각 기록.
     *
     * <p>VERIFY 내부 수렴은 markReady 가 기록하나 DEGRADED 경유로 조정 루프가 전이하는 경로는
     * 미기록이라 단계별 소요 시간의 완료 구간이 공백. 전이마다 갱신하면 최초 도달 시각이
     * 유실되므로 값이 비어 있을 때만 기록.
     */
    static void stampReadyAt(VmClusterEntity vmCluster, VmClusterStatus next) {
        if (next == VmClusterStatus.READY && vmCluster.getReadyAt() == null) {
            vmCluster.setReadyAt(java.time.LocalDateTime.now());
        }
    }

    /** {@code observe} 가 영속화한 상태 조회. 재probe 시 클러스터당 SSH 2회 연결. */
    private void repairUnsatisfied(VmClusterEntity vmCluster, List<ConvergenceSignal> signals) {
        if (evaluate(signals) != ConvergenceVerdict.UNSATISFIED) {
            return;
        }
        for (ComponentObservation observation : observer.currentComponents(vmCluster.getId())) {
            if (observation.requirement() != Requirement.REQUIRED
                    || observation.health() != ComponentHealth.NOT_READY) {
                continue;
            }
            repairService.repairIfDue(vmCluster, observation.type());
        }
    }

    /** 구성 요소 관측과 요청 addon 상태를 단일 단위로 처리. 양쪽 실패를 동등하게 판정. */
    static List<ConvergenceSignal> collectSignals(
            ClusterComponentObserver observer, RequestedAddonInspector inspector, VmClusterEntity vmCluster) {
        List<ConvergenceSignal> signals = new java.util.ArrayList<>(observer.observe(vmCluster).stream()
                .map(ComponentObservation::toSignal)
                .toList());
        signals.addAll(inspector.inspect(vmCluster));
        return signals;
    }

    /** UNSATISFIED 존재 시 전체 판정 UNSATISFIED. INCONCLUSIVE 는 후순위 판정. */
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
