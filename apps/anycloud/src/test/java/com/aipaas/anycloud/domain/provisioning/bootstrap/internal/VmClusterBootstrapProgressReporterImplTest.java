package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter.BootstrapSubStep;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * UX #3 — bootstrap sub-step progress reporting 회귀 보호.
 *
 * <p>BOOTSTRAP step 안에서 6개 sub-step 시작 시점마다 percent 35→64 범위로 갱신.
 * 사용자 "stuck at 66%" 인식 완화.
 */
class VmClusterBootstrapProgressReporterImplTest extends AbstractUnitTest {

    private final OperationService operationService = Mockito.mock(OperationService.class);
    private final VmClusterRepository vmClusterRepository = Mockito.mock(VmClusterRepository.class);
    private final VmClusterBootstrapProgressReporterImpl reporter =
            new VmClusterBootstrapProgressReporterImpl(operationService, vmClusterRepository);

    private OperationEntity activeOp(OperationState state) {
        OperationEntity op = new OperationEntity();
        op.setId("op-1");
        op.setState(state);
        return op;
    }

    @Test
    void report_runningOperation_callsUpdateProgressOnly() {
        when(operationService.findLatestActiveByResource("cluster", "c1"))
                .thenReturn(Optional.of(activeOp(OperationState.RUNNING)));

        reporter.reportSubStepStart("c1", BootstrapSubStep.MASTER_INIT);

        verify(operationService, never()).markRunning(anyString());
        verify(operationService).updateProgress(eq("op-1"), eq("BOOTSTRAP_MASTER_INIT"), eq(2), eq(42));
    }

    @Test
    void report_pendingOperation_marksRunningFirst() {
        when(operationService.findLatestActiveByResource("cluster", "c1"))
                .thenReturn(Optional.of(activeOp(OperationState.PENDING)));

        reporter.reportSubStepStart("c1", BootstrapSubStep.NODE_PREPARATION);

        verify(operationService).markRunning("op-1");
        verify(operationService).updateProgress(eq("op-1"), eq("BOOTSTRAP_NODE_PREPARATION"), eq(2), eq(35));
    }

    @Test
    void report_noActiveOperation_skipsSilently() {
        when(operationService.findLatestActiveByResource("cluster", "ghost")).thenReturn(Optional.empty());

        reporter.reportSubStepStart("ghost", BootstrapSubStep.ADDONS);

        verify(operationService, never()).markRunning(anyString());
        verify(operationService, never()).updateProgress(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void report_operationServiceThrows_doesNotPropagate() {
        // Reporter 는 best-effort — operationService 가 죽어도 bootstrap 흐름 멈추면 안 됨.
        when(operationService.findLatestActiveByResource(any(), any())).thenThrow(new RuntimeException("DB down"));

        // 예외 propagation 없이 정상 반환되어야 함.
        reporter.reportSubStepStart("c1", BootstrapSubStep.WORKER_JOIN);
    }

    @Test
    void subStepPercents_areMonotonicAndWithinBootstrapRange() {
        // PROVISION=33, BOOTSTRAP=66 (post), VERIFY=90 사이.
        // Bootstrap sub-step percent 는 33 초과, 66 이하여야 함 (단조 증가).
        int last = 33;
        for (BootstrapSubStep step : BootstrapSubStep.values()) {
            int p = step.percent();
            assert p > 33 : step + " percent must be > 33 (PROVISION)";
            assert p < 66 : step + " percent must be < 66 (BOOTSTRAP completion)";
            assert p > last : step + " percent must increase (was " + last + ", got " + p + ")";
            last = p;
        }
    }

    @Test
    void allSubStepsHaveDistinctLabels() {
        BootstrapSubStep[] all = BootstrapSubStep.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assert !all[i].label().equals(all[j].label()) : all[i] + " and " + all[j] + " share label";
            }
        }
    }

    @Test
    void allSubStepLabels_startWithBootstrapPrefix() {
        // UI 가 "BOOTSTRAP_" prefix 로 stage 그룹핑 가능하도록 보장.
        for (BootstrapSubStep step : BootstrapSubStep.values()) {
            assert step.label().startsWith("BOOTSTRAP_") : step + " label must start with BOOTSTRAP_";
        }
    }

    @Test
    void verifyTimesUpdateProgressCalledOnce() {
        when(operationService.findLatestActiveByResource("cluster", "c1"))
                .thenReturn(Optional.of(activeOp(OperationState.RUNNING)));

        reporter.reportSubStepStart("c1", BootstrapSubStep.NODES_READY);

        verify(operationService, times(1)).updateProgress(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void report_persistsSubStepOnVmClusterEntity() {
        //  operation 갱신과 별개로 cluster entity 에도 sub-step 기록 (N+1 없이 노출).
        when(operationService.findLatestActiveByResource("cluster", "c1"))
                .thenReturn(Optional.of(activeOp(OperationState.RUNNING)));
        VmClusterEntity vm = new VmClusterEntity();
        when(vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc("c1"))
                .thenReturn(Optional.of(vm));

        reporter.reportSubStepStart("c1", BootstrapSubStep.MASTER_INIT);

        ArgumentCaptor<VmClusterEntity> captor = ArgumentCaptor.forClass(VmClusterEntity.class);
        verify(vmClusterRepository).save(captor.capture());
        assert "BOOTSTRAP_MASTER_INIT".equals(captor.getValue().getCurrentSubStep());
        assert captor.getValue().getSubStepStartedAt() != null;
    }

    @Test
    void report_vmRepositoryThrows_doesNotPropagate() {
        // entity 영속 실패도 best-effort — bootstrap 흐름 멈추면 안 됨.
        when(operationService.findLatestActiveByResource("cluster", "c1"))
                .thenReturn(Optional.of(activeOp(OperationState.RUNNING)));
        when(vmClusterRepository.findFirstByClusterNameOrderByCreatedAtDesc("c1"))
                .thenThrow(new RuntimeException("DB down"));

        reporter.reportSubStepStart("c1", BootstrapSubStep.WORKER_JOIN);
    }
}
