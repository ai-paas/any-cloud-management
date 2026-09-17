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

    /** CSP 호출이 늘어나므로 끌 수 있어야 한다. */
    @org.springframework.beans.factory.annotation.Value("${anycloud.vm-cluster.destroy.precheck:true}")
    private boolean precheckEnabled;

    @Override
    public void execute(String clusterName) {
        // 같은 이름의 세대가 여럿이면 전부 DELETING 으로 들어온다. 스택은 하나지만 행은 모두
        // 닫아야 한다 — 최신 1건만 닫으면 옛 행이 DELETING 에 영원히 남는다.
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

            // 화면에서만 상태가 바뀌는 것이 아니다. 누가 콘솔에서 직접 지우면 우리 기록과 어긋나는데,
            // 확인하지 않으면 그걸 모른 채 destroy 를 돌린다.
            DestroyPrecheck precheck = precheck(vmCluster, credentialEnvironment);
            if (precheck.note() != null) {
                log.info("삭제 전 확인 cluster={}: {}", clusterName, precheck.note());
            }
            // 확인 결과와 무관하게 돌린다. destroy 는 멱등이고, 건너뛰면 만들다 만 자원이 남는다.
            provisioningService.destroy(vmCluster.getStackName(), credentialEnvironment);

            Optional<ClusterEntity> cluster = clusterRepository.findById(clusterName);
            cluster.ifPresent(clusterRepository::delete);

            // 스택은 하나지만 그 이름의 DELETING 행은 모두 닫는다.
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
     * CSP 를 실제로 본다. 확인하지 못해도 삭제는 진행한다 — 막으면 CSP 장애 때 정리할 방법이 없어진다.
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
