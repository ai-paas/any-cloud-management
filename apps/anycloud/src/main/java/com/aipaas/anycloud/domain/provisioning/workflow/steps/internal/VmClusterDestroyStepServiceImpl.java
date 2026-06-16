package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterStepExecutionException;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterDestroyStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
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
    private final PulumiProvisioningService pulumiProvisioningService;
    private final VmClusterWorkflowSupportService workflowSupportService;

    @Override
    public void execute(String clusterName) {
        VmClusterEntity vmCluster = workflowSupportService.getLatestVmCluster(clusterName);
        // 2차 멱등성 가드: 이미 DELETED 상태인 클러스터의 DESTROY 중복 진입 차단.
        if (com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.DESTROY.isStaleForStatus(
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
                    vmCluster.getClusterProvider(), vmCluster.getCredentialId(), vmCluster.getCredentialSourceType());
            pulumiProvisioningService.destroy(vmCluster.getStackName(), credentialEnvironment);

            Optional<ClusterEntity> cluster = clusterRepository.findById(clusterName);
            cluster.ifPresent(clusterRepository::delete);

            workflowSupportService.markDeleteCompleted(vmCluster);
        } catch (Exception e) {
            workflowSupportService.failWithDiagnostics(vmCluster, clusterName, e);
            throw new VmClusterStepExecutionException("DESTROY step failed for " + clusterName, e);
        }
    }
}
