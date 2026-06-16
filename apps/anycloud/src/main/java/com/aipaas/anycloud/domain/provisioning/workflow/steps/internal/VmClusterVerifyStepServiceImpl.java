package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.common.error.exception.provisioning.TransientProvisioningFailure;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterStepExecutionException;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterVerifyStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterVerifyStepServiceImpl implements VmClusterVerifyStepService {

    private final VmClusterRepository vmClusterRepository;
    private final ClusterService clusterService;
    private final VmClusterWorkflowSupportService workflowSupportService;
    private final io.aipaas.cluster.provisioning.service.PulumiProvisioningService pulumiProvisioningService;
    private final com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService remoteAccessService;

    @Override
    public void execute(String vmClusterId, String clusterName) {
        VmClusterEntity vmCluster = workflowSupportService.getVmClusterById(vmClusterId, clusterName);
        // 2차 멱등성 가드: READY 또는 삭제 단계에 도달한 클러스터의 VERIFY 중복 진입 차단.
        if (com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.VERIFY.isStaleForStatus(
                vmCluster.getProvisioningStatus())) {
            log.info(
                    "Verify step skipped: cluster {} already past VERIFY (status={})",
                    clusterName,
                    vmCluster.getProvisioningStatus());
            return;
        }
        try {
            workflowSupportService.markStepStarted(
                    vmCluster,
                    com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.VERIFY,
                    VmClusterStatus.VERIFYING,
                    false);

            // VM cluster 검증은 bootstrap 과 동일한 SSH transport 사용. agent 경유는 AGENT_PENDING
            // 신규 cluster 에서 항상 실패 — agent dial-in 은 READY 이후 단계.
            String readyz = remoteAccessService.runOnMaster(
                    vmCluster,
                    pulumiProvisioningService.stackOutputs(vmCluster.getStackName(), true, java.util.Map.of()),
                    "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl get --raw=/readyz",
                    java.time.Duration.ofMinutes(2));
            if (readyz == null || !readyz.trim().endsWith("ok")) {
                // kubelet/API server 가 아직 ready 신호를 못 줌. Bootstrap 직후 흔히 발생하는
                // 일시 상태로 retry interceptor 가 maxAttempts 까지 재시도하면 일반적으로 통과.
                throw new TransientProvisioningFailure("Cluster readyz verification failed: " + readyz);
            }
            // Agent 상태 동기화는 best-effort — agent 미설치가 VERIFY 실패 사유가 되면 안 됨.
            try {
                clusterService.refreshClusterStatus(clusterName);
            } catch (Exception e) {
                log.warn(
                        "refreshClusterStatus best-effort failed for {} (agent 미설치 가능): {}", clusterName, e.toString());
            }

            workflowSupportService.markReady(vmCluster);
            log.info("VM cluster workflow completed for cluster {}", clusterName);
        } catch (Exception e) {
            workflowSupportService.failWithDiagnostics(vmCluster, clusterName, e);
            throw new VmClusterStepExecutionException("VERIFY step failed for " + clusterName, e);
        }
    }
}
