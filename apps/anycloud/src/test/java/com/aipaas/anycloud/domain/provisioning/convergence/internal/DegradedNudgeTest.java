package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterConvergenceOrchestrator;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.scheduling.TaskScheduler;

/**
 * DEGRADED 직후 1회 추가 확인.
 *
 * <p>VERIFY 가 수렴 예산을 소진하고 DEGRADED 로 전이하면 기존에는 5분 주기 정기 조정 전체를
 * 대기. 실측 DEGRADED → READY 소요 9분 22초, 대부분이 대기 시간. 애드온 기동 가능성이 가장
 * 높은 시점이라 추가 확인 적용.
 */
class DegradedNudgeTest extends AbstractUnitTest {

    @Mock
    ClusterConvergenceOrchestrator orchestrator;

    @Mock
    VmClusterRepository vmClusterRepository;

    @Mock
    TaskScheduler taskScheduler;

    private ClusterDegradedNudge nudge;
    private final AtomicReference<Runnable> scheduled = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        nudge = new ClusterDegradedNudge(orchestrator, vmClusterRepository, taskScheduler, Duration.ofSeconds(30));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenAnswer(inv -> {
                    scheduled.set(inv.getArgument(0));
                    return null;
                });
    }

    private VmClusterEntity cluster(VmClusterStatus status) {
        VmClusterEntity e = new VmClusterEntity();
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        return e;
    }

    @Test
    void schedulesARecheckAfterTheConfiguredDelay() {
        nudge.onDegraded("demo");

        verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    void recheckDrivesConvergenceForThatClusterOnly() {
        when(vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc("demo"))
                .thenReturn(Optional.of(cluster(VmClusterStatus.DEGRADED)));

        nudge.onDegraded("demo");
        scheduled.get().run();

        verify(orchestrator).driveOne("demo");
    }

    @Test
    void recheckSkipsClustersThatMovedOn() {
        // 재확인 실행 중 사용자 삭제 가능. 복원 금지.
        when(vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc("demo"))
                .thenReturn(Optional.of(cluster(VmClusterStatus.DELETING)));

        nudge.onDegraded("demo");
        scheduled.get().run();

        verify(orchestrator, never()).driveOne("demo");
    }

    @Test
    void recheckSurvivesOrchestratorFailure() {
        // 재확인은 보조 기능. 실패로 스케줄러 스레드 종료 금지.
        when(vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc("demo"))
                .thenReturn(Optional.of(cluster(VmClusterStatus.DEGRADED)));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(orchestrator)
                .driveOne("demo");

        nudge.onDegraded("demo");

        assertThat(scheduled.get()).isNotNull();
        scheduled.get().run();
    }
}
