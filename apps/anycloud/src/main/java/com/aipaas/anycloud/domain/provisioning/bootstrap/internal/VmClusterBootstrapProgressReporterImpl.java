package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VmClusterBootstrapProgressReporterImpl implements VmClusterBootstrapProgressReporter {

    private final OperationService operationService;
    private final VmClusterRepository vmClusterRepository;

    @Override
    public void reportSubStepStart(String clusterName, BootstrapSubStep subStep) {
        try {
            operationService.findLatestActiveByResource("cluster", clusterName).ifPresent(op -> {
                if (op.getState() == OperationState.PENDING) {
                    operationService.markRunning(op.getId());
                }
                // stepIndex 는 BOOTSTRAP(2) 안의 sub-step 위치 — 2 그대로 두고 currentStep 으로
                // 세부 정보 전달. UI 가 percent + currentStep 둘 다 표시 가능.
                operationService.updateProgress(op.getId(), subStep.label(), 2, subStep.percent());
            });
        } catch (Exception ex) {
            // Best-effort — bootstrap 흐름은 멈추면 금지.
            log.warn(
                    "Bootstrap progress report skipped (cluster {}, step {}): {}", clusterName, subStep, ex.toString());
        }
        //  cluster entity 에도 sub-step 기록 — cluster list/get 의 workflowProgress 가
        // operation 재조회 (N+1) 없이 sub-step 을 노출하도록. operation 갱신과 독립된 best-effort.
        try {
            vmClusterRepository
                    .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                    .ifPresent(vm -> {
                        vm.setCurrentSubStep(subStep.label());
                        vm.setSubStepStartedAt(LocalDateTime.now());
                        vmClusterRepository.save(vm);
                    });
        } catch (Exception ex) {
            log.warn(
                    "Bootstrap sub-step persist skipped (cluster {}, step {}): {}",
                    clusterName,
                    subStep,
                    ex.toString());
        }
    }
}
