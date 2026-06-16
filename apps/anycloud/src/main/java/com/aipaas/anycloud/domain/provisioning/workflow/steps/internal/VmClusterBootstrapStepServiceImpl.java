package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapLogService;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.registration.VmClusterRegistrationService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterKubeconfigService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterStepExecutionException;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterBootstrapStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * VM cluster BOOTSTRAP workflow step. 12 dependency 를 5 책임 group 으로 정리:
 *
 * <ol>
 *   <li>Repository / state — {@code vmClusterRepository}. step 진입 / 갱신.</li>
 *   <li>External lookup — {@code cspCredentialService}, {@code pulumiProvisioningService}. CSP env
 *       + Pulumi stack outputs.</li>
 *   <li>Bootstrap execution — {@code vmClusterBootstrapService}, {@code vmClusterKubeconfigService}
 *       (SSH 기반 kubeadm + kubeconfig collection).</li>
 *   <li>Post-bootstrap registration — {@code vmClusterRegistrationService},
 *       {@code clusterAgentInstaller}. cluster row insert + agent helm install.</li>
 *   <li>Workflow continuation — {@code vmClusterWorkflowPublisher},
 *       {@code vmClusterWorkflowSupportService}, {@code vmClusterPayloadService},
 *       {@code asyncTaskExecutor}. 다음 step (VERIFY) publish + state mutation.</li>
 * </ol>
 *
 * <p>가장 명백한 분해 candidate 는 4 의 post-bootstrap registration — SSH path 의 best-effort
 * agent install 이 BOOTSTRAP 단계의 핵심 경로와 분리되어 있어 별도 {@code AgentInstaller} class
 * 로 추출 가능. 분해 후 deps 7 으로 감소.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterBootstrapStepServiceImpl implements VmClusterBootstrapStepService {

    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final PulumiProvisioningService pulumiProvisioningService;
    private final VmClusterBootstrapService vmClusterBootstrapService;
    private final VmClusterKubeconfigService vmClusterKubeconfigService;
    private final VmClusterRegistrationService vmClusterRegistrationService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final VmClusterWorkflowPublisher vmClusterWorkflowPublisher;
    private final VmClusterWorkflowSupportService workflowSupportService;
    private final VmClusterBootstrapLogService vmClusterBootstrapLogService;
    private final VmClusterAgentInstaller agentInstaller;

    @Override
    public void execute(String vmClusterId, String clusterName) {
        VmClusterEntity vmCluster = workflowSupportService.getVmClusterById(vmClusterId, clusterName);
        // 2차 멱등성 가드: 이미 VERIFY/READY/삭제 단계인 클러스터에 BOOTSTRAP 중복 진입 차단.
        if (VmClusterWorkflowStep.BOOTSTRAP.isStaleForStatus(vmCluster.getProvisioningStatus())) {
            log.info(
                    "Bootstrap step skipped: cluster {} already past BOOTSTRAP (status={})",
                    clusterName,
                    vmCluster.getProvisioningStatus());
            return;
        }
        try {
            workflowSupportService.markStepStarted(
                    vmCluster, VmClusterWorkflowStep.BOOTSTRAP, VmClusterStatus.BOOTSTRAPPING, false);
            // Retry 시에도 이전 attempt 의 log 를 truncate 하지 않고 attempt 마커만 추가 — 디버깅용
            // 누적 가시성 보존. workflowRetryCount 는 0-based 라 +1 해서 attempt 번호로 표시.
            int attemptNumber = (vmCluster.getWorkflowRetryCount() == null ? 0 : vmCluster.getWorkflowRetryCount()) + 1;
            vmClusterBootstrapLogService.appendAttemptMarker(vmCluster, attemptNumber);
            vmClusterRepository.save(vmCluster);

            Map<String, String> credentialEnvironment = cspCredentialService.resolveEnvironment(
                    vmCluster.getClusterProvider(), vmCluster.getCredentialId(), vmCluster.getCredentialSourceType());
            Map<String, Object> outputs =
                    pulumiProvisioningService.stackOutputs(vmCluster.getStackName(), true, credentialEnvironment);

            vmClusterBootstrapService.bootstrap(vmCluster, outputs);
            String kubeconfigContent = vmClusterKubeconfigService.fetchKubeconfig(vmCluster, outputs);
            vmClusterRegistrationService.registerFromKubeconfig(vmCluster, kubeconfigContent);
            vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
            vmCluster.setClusterRegistered(true);
            vmClusterRepository.save(vmCluster);

            agentInstaller.installViaSsh(vmCluster, outputs);

            workflowSupportService.markStepSucceeded(vmCluster, VmClusterWorkflowStep.BOOTSTRAP);

            vmClusterWorkflowPublisher.publishVerify(VmClusterWorkflowMessage.builder()
                    .vmClusterId(vmClusterId)
                    .clusterName(clusterName)
                    .stackName(vmCluster.getStackName())
                    .step(VmClusterWorkflowStep.VERIFY)
                    .build());
        } catch (Exception e) {
            workflowSupportService.fail(vmCluster, clusterName, e);
            throw new VmClusterStepExecutionException("BOOTSTRAP step failed for " + clusterName, e);
        }
    }
}
