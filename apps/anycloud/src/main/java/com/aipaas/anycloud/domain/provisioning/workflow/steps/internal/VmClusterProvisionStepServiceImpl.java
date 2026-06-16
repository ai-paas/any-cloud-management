package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterStepExecutionException;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import com.aipaas.anycloud.domain.provisioning.workflow.steps.VmClusterProvisionStepService;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterProvisionStepServiceImpl implements VmClusterProvisionStepService {

    private final VmClusterRepository vmClusterRepository;
    private final PulumiProvisioningService pulumiProvisioningService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final VmClusterWorkflowPublisher vmClusterWorkflowPublisher;
    private final VmClusterWorkflowSupportService workflowSupportService;
    private final ProvisioningProviderValidator provisioningProviderValidator;

    @Override
    public void execute(String vmClusterId, String clusterName, ProvisioningRequest request) {
        VmClusterEntity vmCluster = workflowSupportService.getVmClusterById(vmClusterId, clusterName);
        // 2차 멱등성 가드: orchestrator 가드를 우회하는 경로(테스트/수동 호출)에서도 동작.
        if (VmClusterWorkflowStep.PROVISION.isStaleForStatus(vmCluster.getProvisioningStatus())) {
            log.info(
                    "Provision step skipped: cluster {} already past PROVISION (status={})",
                    clusterName,
                    vmCluster.getProvisioningStatus());
            return;
        }
        try {
            workflowSupportService.markStepStarted(
                    vmCluster, VmClusterWorkflowStep.PROVISION, VmClusterStatus.PROVISIONING, true);

            // Live selection 검증 — controller sync 경로에서 이쪽으로 이동됨.
            // Provider 별 CSP API (DescribeInstanceTypes 등) 호출로 instance type / image 존재 여부 확인.
            // 잘못된 selection 이면 즉시 fail — Pulumi up 시도하지 않음.
            provisioningProviderValidator.validateLive(request);

            Map<String, Object> outputs = pulumiProvisioningService.provision(request);
            vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
            vmClusterRepository.save(vmCluster);
            workflowSupportService.markStepSucceeded(vmCluster, VmClusterWorkflowStep.PROVISION);

            vmClusterWorkflowPublisher.publishBootstrap(VmClusterWorkflowMessage.builder()
                    .vmClusterId(vmClusterId)
                    .clusterName(clusterName)
                    .stackName(vmCluster.getStackName())
                    .step(VmClusterWorkflowStep.BOOTSTRAP)
                    .build());
        } catch (Exception e) {
            workflowSupportService.fail(vmCluster, clusterName, e);
            throw new VmClusterStepExecutionException("PROVISION step failed for " + clusterName, e);
        }
    }
}
