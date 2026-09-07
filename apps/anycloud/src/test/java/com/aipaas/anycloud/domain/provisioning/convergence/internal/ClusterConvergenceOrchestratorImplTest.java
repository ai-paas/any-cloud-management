package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentRepairService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentObservation;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.ConvergenceSignal;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterConvergenceOrchestratorImplTest {

    private final ClusterComponentObserver observer = mock(ClusterComponentObserver.class);
    private final RequestedAddonInspector addonInspector = mock(RequestedAddonInspector.class);
    private final VmClusterRepository repository = mock(VmClusterRepository.class);
    private final ClusterComponentRepairService repairService = mock(ClusterComponentRepairService.class);
    private final ClusterConvergenceOrchestratorImpl orchestrator =
            new ClusterConvergenceOrchestratorImpl(observer, addonInspector, repository, repairService);

    private VmClusterEntity cluster(VmClusterStatus status) {
        VmClusterEntity vmCluster = new VmClusterEntity();
        vmCluster.setId("vmc-001");
        vmCluster.setClusterName("demo-aws-01");
        vmCluster.setProvisioningStatus(status);
        return vmCluster;
    }

    private ConvergenceSignal signal(Requirement requirement, ComponentHealth health) {
        return new ConvergenceSignal("AGENT", requirement, health, null);
    }

    private ComponentObservation observation(Requirement requirement, ComponentHealth health) {
        return new ComponentObservation(ComponentType.AGENT, requirement, health, null);
    }

    @Test
    void evaluate_satisfiedWhenAllRequiredReady() {
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(
                        List.of(signal(Requirement.REQUIRED, ComponentHealth.READY))))
                .isEqualTo(ConvergenceVerdict.SATISFIED);
    }

    @Test
    void evaluate_ignoresBestEffortComponents() {
        // BEST_EFFORT 미충족이 READY 를 막으면 개발 환경이 전부 DEGRADED 가 된다.
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(List.of(
                        signal(Requirement.REQUIRED, ComponentHealth.READY),
                        signal(Requirement.BEST_EFFORT, ComponentHealth.NOT_READY))))
                .isEqualTo(ConvergenceVerdict.SATISFIED);
    }

    @Test
    void evaluate_unsatisfiedWhenRequiredNotReady() {
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(
                        List.of(signal(Requirement.REQUIRED, ComponentHealth.NOT_READY))))
                .isEqualTo(ConvergenceVerdict.UNSATISFIED);
    }

    @Test
    void evaluate_inconclusiveWhenRequiredUnknown() {
        // 관측 실패로 상태를 내리면 네트워크가 흔들릴 때마다 클러스터가 오간다.
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(
                        List.of(signal(Requirement.REQUIRED, ComponentHealth.UNKNOWN))))
                .isEqualTo(ConvergenceVerdict.INCONCLUSIVE);
    }

    @Test
    void evaluate_unsatisfiedWinsOverUnknown() {
        // 하나라도 확실히 미충족이면 UNKNOWN 이 섞여 있어도 판정은 미충족이다.
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(List.of(
                        signal(Requirement.REQUIRED, ComponentHealth.UNKNOWN),
                        signal(Requirement.REQUIRED, ComponentHealth.NOT_READY))))
                .isEqualTo(ConvergenceVerdict.UNSATISFIED);
    }

    @Test
    void evaluate_satisfiedWhenNothingApplicable() {
        assertThat(ClusterConvergenceOrchestratorImpl.evaluate(List.of())).isEqualTo(ConvergenceVerdict.SATISFIED);
    }

    @Test
    void drive_promotesDegradedToReadyWhenSatisfied() {
        VmClusterEntity vmCluster = cluster(VmClusterStatus.DEGRADED);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster)).thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.READY)));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.READY);
        verify(repository).save(vmCluster);
    }

    @Test
    void drive_demotesReadyToDegradedOnDrift() {
        VmClusterEntity vmCluster = cluster(VmClusterStatus.READY);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster))
                .thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.NOT_READY)));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.DEGRADED);
    }

    @Test
    void drive_leavesStatusAloneWhenInconclusive() {
        VmClusterEntity vmCluster = cluster(VmClusterStatus.READY);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster))
                .thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.UNKNOWN)));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.READY);
        verify(repository, never()).save(any());
    }

    @Test
    void drive_continuesAfterOneClusterThrows() {
        // 클러스터 하나의 실패가 나머지 조정을 멈추면 안 된다.
        VmClusterEntity broken = cluster(VmClusterStatus.DEGRADED);
        broken.setId("vmc-broken");
        VmClusterEntity healthy = cluster(VmClusterStatus.DEGRADED);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(broken, healthy));
        when(observer.observe(broken)).thenThrow(new RuntimeException("boom"));
        when(observer.observe(healthy)).thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.READY)));

        orchestrator.drive();

        assertThat(healthy.getProvisioningStatus()).isEqualTo(VmClusterStatus.READY);
    }

    @Test
    void drive_degradesWhenRequestedAddonFailed() {
        // 구성 요소는 멀쩡해도 요청한 addon 이 실패했으면 요청대로 준비된 게 아니다.
        VmClusterEntity vmCluster = cluster(VmClusterStatus.READY);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster)).thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.READY)));
        when(addonInspector.inspect(vmCluster))
                .thenReturn(List.of(new ConvergenceSignal(
                        "nvidia-gpu-operator", Requirement.REQUIRED, ComponentHealth.NOT_READY, "helm 실패")));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.DEGRADED);
    }

    @Test
    void drive_staysReadyWhenAddonInstallStillInFlight() {
        // 설치 중(UNKNOWN)을 실패로 보면 정상 진행 중인 클러스터가 DEGRADED 로 떨어진다.
        VmClusterEntity vmCluster = cluster(VmClusterStatus.READY);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster)).thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.READY)));
        when(addonInspector.inspect(vmCluster))
                .thenReturn(List.of(new ConvergenceSignal(
                        "nvidia-gpu-operator", Requirement.REQUIRED, ComponentHealth.UNKNOWN, "설치 진행 중")));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.READY);
        verify(repository, never()).save(any());
    }

    @Test
    void drive_promotesWhenBothComponentAndAddonReady() {
        VmClusterEntity vmCluster = cluster(VmClusterStatus.DEGRADED);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster)).thenReturn(List.of(observation(Requirement.REQUIRED, ComponentHealth.READY)));
        when(addonInspector.inspect(vmCluster))
                .thenReturn(List.of(new ConvergenceSignal(
                        "nvidia-gpu-operator", Requirement.REQUIRED, ComponentHealth.READY, null)));

        orchestrator.drive();

        assertThat(vmCluster.getProvisioningStatus()).isEqualTo(VmClusterStatus.READY);
    }

    private void driveWith(ConvergenceSignal signal, ComponentObservation observation) {
        VmClusterEntity vmCluster = cluster(VmClusterStatus.DEGRADED);
        when(repository.findByProvisioningStatusIn(any())).thenReturn(List.of(vmCluster));
        when(observer.observe(vmCluster)).thenReturn(List.of(observation));
        when(observer.currentComponents("vmc-001")).thenReturn(List.of(observation));
        when(addonInspector.inspect(vmCluster)).thenReturn(List.of());
        orchestrator.drive();
    }

    @Test
    void drive_repairsRequiredComponentThatIsNotReady() {
        // 이 호출이 없으면 조정 루프는 상태만 다시 매기고 아무것도 고치지 않는다.
        driveWith(
                signal(Requirement.REQUIRED, ComponentHealth.NOT_READY),
                observation(Requirement.REQUIRED, ComponentHealth.NOT_READY));

        verify(repairService).repairIfDue(any(), eq(ComponentType.AGENT));
    }

    @Test
    void drive_doesNotRepairWhenSatisfied() {
        driveWith(
                signal(Requirement.REQUIRED, ComponentHealth.READY),
                observation(Requirement.REQUIRED, ComponentHealth.READY));

        verify(repairService, never()).repairIfDue(any(), any());
    }

    @Test
    void drive_doesNotRepairBestEffortComponent() {
        // BEST_EFFORT 는 미충족이어도 판정을 UNSATISFIED 로 만들지 않는다. 고칠 대상도 아니다.
        driveWith(
                signal(Requirement.BEST_EFFORT, ComponentHealth.NOT_READY),
                observation(Requirement.BEST_EFFORT, ComponentHealth.NOT_READY));

        verify(repairService, never()).repairIfDue(any(), any());
    }

    @Test
    void drive_doesNotReprobeForRepairTargets() {
        // observe 가 이미 영속화했다. 여기서 다시 probe 하면 클러스터마다 SSH 가 두 번 열린다.
        driveWith(
                signal(Requirement.REQUIRED, ComponentHealth.NOT_READY),
                observation(Requirement.REQUIRED, ComponentHealth.NOT_READY));

        verify(observer, org.mockito.Mockito.times(1)).observe(any());
    }
}
