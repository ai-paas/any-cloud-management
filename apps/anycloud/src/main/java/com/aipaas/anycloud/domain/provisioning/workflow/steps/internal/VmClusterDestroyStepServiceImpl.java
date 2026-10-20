package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.command.VmClusterDeletionTargets;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.DestroyPrecheck;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterStepExecutionException;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterDestroyStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterDestroyStepServiceImpl implements VmClusterDestroyStepService {

    private final ClusterRepository clusterRepository;
    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final ProvisioningService provisioningService;
    private final VmClusterWorkflowSupportService workflowSupportService;

    /** CSP 호출 증가를 동반하므로 비활성화 가능해야 함. */
    @org.springframework.beans.factory.annotation.Value("${anycloud.vm-cluster.destroy.precheck:true}")
    private boolean precheckEnabled;

    @Override
    public void execute(String clusterName) {
        // 동일 이름 세대가 여럿이면 전부 DELETING 진입. 스택은 하나지만 행은 전부 종료 처리
        // 필요. 최신 1건만 처리하면 이전 행이 DELETING 에 잔존.
        List<VmClusterEntity> deleting = VmClusterDeletionTargets.beingDeleted(
                vmClusterRepository.findAllByClusterNameOrderByCreatedAtDesc(clusterName));

        VmClusterEntity vmCluster =
                deleting.isEmpty() ? workflowSupportService.getLatestVmCluster(clusterName) : deleting.get(0);

        // 2차 멱등성 가드: 이미 DELETED 상태인 클러스터의 DESTROY 중복 진입 차단.
        if (deleting.isEmpty()
                && com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.DESTROY.isStaleForStatus(
                        vmCluster.getProvisioningStatus())) {
            log.info("Destroy step skipped: cluster {} already DELETED", clusterName);
            return;
        }
        try {
            workflowSupportService.markStepStarted(
                    vmCluster,
                    com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.DESTROY,
                    VmClusterStatus.DELETING,
                    false);

            Map<String, String> credentialEnvironment = cspCredentialService.resolveEnvironment(
                    vmCluster.getClusterProvider(), vmCluster.getCredentialId());

            // 상태 변경 경로는 화면 외에도 존재. 콘솔에서 직접 삭제 시 기록과 불일치하며,
            // 미확인 상태로 destroy 실행.
            DestroyPrecheck precheck = precheck(vmCluster, credentialEnvironment);
            if (precheck.note() != null) {
                log.info("삭제 전 확인 cluster={}: {}", clusterName, precheck.note());
            }
            // 확인 결과와 무관하게 실행. destroy 는 멱등이며 생략 시 생성 중단된 자원 잔존.
            provisioningService.destroy(vmCluster.getStackName(), credentialEnvironment);

            Optional<ClusterEntity> cluster = clusterRepository.findById(clusterName);
            cluster.ifPresent(clusterRepository::delete);

            // 스택은 하나지만 해당 이름의 DELETING 행은 전부 종료 처리.
            if (deleting.isEmpty()) {
                workflowSupportService.markDeleteCompleted(vmCluster);
            } else {
                deleting.forEach(workflowSupportService::markDeleteCompleted);
            }
        } catch (Exception e) {
            workflowSupportService.failWithDiagnostics(vmCluster, clusterName, e);
            throw new VmClusterStepExecutionException("DESTROY step failed for " + clusterName, e);
        }
    }
    /**
     * CSP 실제 상태 조회. 확인 실패 시에도 삭제 진행. 차단 시 CSP 장애 상황에서 정리 수단 부재.
     */
    private DestroyPrecheck precheck(VmClusterEntity vmCluster, Map<String, String> credentialEnvironment) {
        if (!precheckEnabled) {
            return DestroyPrecheck.skipped();
        }
        try {
            Map<String, Object> outputs =
                    provisioningService.stackOutputs(vmCluster.getStackName(), false, credentialEnvironment);
            return DestroyPrecheck.refreshed(outputs == null ? 0 : outputs.size());
        } catch (Exception e) {
            return DestroyPrecheck.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
