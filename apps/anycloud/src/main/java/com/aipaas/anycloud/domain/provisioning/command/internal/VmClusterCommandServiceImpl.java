package com.aipaas.anycloud.domain.provisioning.command.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.provisioning.StateConflictException;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.command.VmClusterCommandService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.registration.VmClusterRegistrationService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterKubeconfigService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterAsyncService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class VmClusterCommandServiceImpl implements VmClusterCommandService {

    private final ClusterRepository clusterRepository;
    private final VmClusterRepository vmClusterRepository;
    private final VmClusterAsyncService vmClusterAsyncService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final PulumiProvisioningService pulumiProvisioningService;
    private final ProvisioningProviderValidator provisioningProviderValidator;
    private final VmClusterKubeconfigService vmClusterKubeconfigService;
    private final VmClusterRegistrationService vmClusterRegistrationService;
    private final CspCredentialService cspCredentialService;
    private final VmClusterWorkflowPublisher workflowPublisher;

    @Override
    public HttpStatus createVmCluster(ProvisionClusterRequest cluster) {
        if (cluster == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        if (clusterRepository.findById(cluster.getClusterName()).isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE);
        }

        if (vmClusterRepository.existsByActiveRequestKey(cluster.getClusterName())) {
            throw new CustomException(ErrorCode.DUPLICATE);
        }

        vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(cluster.getClusterName())
                .ifPresent(existing -> {
                    if (existing.getProvisioningStatus() != VmClusterStatus.FAILED
                            && existing.getProvisioningStatus() != VmClusterStatus.DELETED) {
                        throw new CustomException(ErrorCode.DUPLICATE);
                    }
                });

        ResolvedCspCredential credential =
                cspCredentialService.resolveForProvision(cluster.getClusterProvider(), cluster.getCredentialId());
        // Sync controller 경로에서는 static 검증만 — provider/region/required config/credential value.
        // Live selection 검증 (CSP API call — instance type/image 존재 여부) 은 provision step
        // 의 첫 단계로 이동했다 (worker async). 잘못된 selection 이면 PROVISION step 이 즉시 FAIL.
        ProvisioningRequest request = provisioningProviderValidator.validateStaticAndBuildRequest(cluster, credential);
        String stackName = pulumiProvisioningService.buildStackName(request);

        VmClusterEntity vmCluster = VmClusterEntity.builder()
                .clusterName(cluster.getClusterName())
                .description(cluster.getDescription())
                .clusterProvider(request.getProvider())
                // provisioningStatus 는 transitionTo 통해 set — state history 의 첫 row 보존.
                .stackName(stackName)
                .region(cluster.getRegion())
                .environment(cluster.getEnvironment())
                .activeRequestKey(cluster.getClusterName())
                .credentialId(credential.getCredentialId())
                .credentialName(credential.getCredentialName())
                .credentialSourceType(credential.getSourceType())
                .requestConfig(vmClusterPayloadService.serializeRequestSnapshot(cluster, request, credential))
                .requestedAt(LocalDateTime.now())
                .clusterRegistered(false)
                .build();
        // 초기 transition (null → REQUESTED) — audit history 의 첫 row 생성.
        vmCluster.transitionTo(VmClusterStatus.REQUESTED, "vmcluster.create");

        try {
            vmClusterRepository.save(vmCluster);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE);
        }
        vmClusterAsyncService.provisionClusterAsync(vmCluster.getId(), cluster.getClusterName(), request);
        return HttpStatus.ACCEPTED;
    }

    @Override
    public HttpStatus retryVmClusterRegistration(String clusterName) {
        VmClusterEntity vmCluster = getVmCluster(clusterName);
        Map<String, String> credentialEnvironment = cspCredentialService.resolveEnvironment(
                vmCluster.getClusterProvider(), vmCluster.getCredentialId(), vmCluster.getCredentialSourceType());
        Map<String, Object> outputs =
                pulumiProvisioningService.stackOutputs(vmCluster.getStackName(), true, credentialEnvironment);

        String kubeconfigContent = vmClusterKubeconfigService.fetchKubeconfig(vmCluster, outputs);
        vmClusterRegistrationService.registerFromKubeconfig(vmCluster, kubeconfigContent);

        vmCluster.setCurrentWorkflowStep(com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.VERIFY);
        vmCluster.setLastSuccessfulStep(com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.VERIFY);
        vmCluster.setWorkflowRetryCount(
                (vmCluster.getWorkflowRetryCount() == null ? 0 : vmCluster.getWorkflowRetryCount()) + 1);
        vmCluster.setVerifyingStartedAt(LocalDateTime.now());
        vmCluster.setClusterRegistered(true);
        vmCluster.transitionTo(VmClusterStatus.READY, "command.ok");
        vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
        vmCluster.setLastError(null);
        vmCluster.setFailedAt(null);
        vmCluster.setReadyAt(LocalDateTime.now());
        vmClusterRepository.save(vmCluster);
        return HttpStatus.OK;
    }

    @Override
    public HttpStatus retryVmClusterWorkflow(String clusterName) {
        VmClusterEntity vmCluster = getVmCluster(clusterName);
        VmClusterStatus current = vmCluster.getProvisioningStatus();
        if (current != VmClusterStatus.BLOCKED && current != VmClusterStatus.FAILED) {
            throw new StateConflictException("Only BLOCKED or FAILED clusters can be retried, current=" + current);
        }

        VmClusterWorkflowStep retryStep =
                vmCluster.getLastFailedStep() != null ? vmCluster.getLastFailedStep() : VmClusterWorkflowStep.BOOTSTRAP;

        if (retryStep == VmClusterWorkflowStep.DESTROY) {
            throw new StateConflictException("DESTROY 단계는 retry 가 아닌 일반 DELETE API 로 재시도하세요.");
        }

        // Pulumi up 은 idempotent — 동일 stack state 에 missing 리소스만 생성, existing 은 skip/update.
        // drift 가 심한 경우 destructive 변경 가능성 있으므로 회복 불가 시 DELETE + recreate 로 fallback.

        VmClusterStatus preStatus =
                switch (retryStep) {
                    case PROVISION -> VmClusterStatus.REQUESTED; // provision 진입 직전
                    case BOOTSTRAP -> VmClusterStatus.PROVISIONING; // bootstrap 진입 직전
                    case VERIFY -> VmClusterStatus.BOOTSTRAPPING; // verify 진입 직전
                    default -> throw new CustomException(
                            "Unsupported retry step: " + retryStep, ErrorCode.INVALID_INPUT_VALUE);
                };

        vmCluster.transitionTo(preStatus, "command.retry." + retryStep.name());
        vmCluster.setWorkflowRetryCount(0);
        vmCluster.setLastError(null);
        vmCluster.setFailedAt(null);
        vmCluster.setLastFailedStep(null);
        // markProcessed 흔적도 초기화하여 새 messageId 의 가드가 정상 통과하도록 한다.
        vmCluster.setLastProcessedWorkflowMessageId(null);
        vmClusterRepository.save(vmCluster);

        VmClusterWorkflowMessage message = VmClusterWorkflowMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .vmClusterId(vmCluster.getId())
                .clusterName(clusterName)
                .stackName(vmCluster.getStackName())
                .step(retryStep)
                .build();

        switch (retryStep) {
            case PROVISION -> workflowPublisher.publishProvision(message);
            case BOOTSTRAP -> workflowPublisher.publishBootstrap(message);
            case VERIFY -> workflowPublisher.publishVerify(message);
            default -> {
                /* unreachable */
            }
        }
        return HttpStatus.ACCEPTED;
    }

    @Override
    public HttpStatus scaleVmCluster(String clusterName, int workerCount) {
        VmClusterEntity vmCluster = getVmCluster(clusterName);
        if (vmCluster.getProvisioningStatus() != VmClusterStatus.READY) {
            throw new StateConflictException(
                    "Only READY clusters can be scaled, current=" + vmCluster.getProvisioningStatus());
        }
        if (workerCount < 1 || workerCount > 50) {
            throw new CustomException(
                    "workerCount out of range (1..50): " + workerCount, ErrorCode.INVALID_INPUT_VALUE);
        }
        vmClusterAsyncService.scaleClusterAsync(clusterName, workerCount);
        return HttpStatus.ACCEPTED;
    }

    @Override
    public HttpStatus deleteVmCluster(String clusterName) {
        VmClusterEntity vmCluster = getVmCluster(clusterName);
        Optional<ClusterEntity> cluster = clusterRepository.findById(clusterName);

        //  DELETE 멱등성. 이미 DELETED 인 history row 에 재삭제 요청이 오면 strict state
        // machine 이 DELETED → DELETING 전환을 거부해 400 이 났었다 — REST DELETE 는 멱등이어야
        // 하므로 no-op 성공으로 처리. history row 는 보존, 잔존 cluster row 만 정리.
        if (vmCluster.getProvisioningStatus() == VmClusterStatus.DELETED) {
            cluster.ifPresent(clusterRepository::delete);
            return HttpStatus.OK;
        }

        if (vmCluster.getStackName() != null && !vmCluster.getStackName().isBlank()) {
            vmCluster.transitionTo(VmClusterStatus.DELETING, "command.delete");
            vmCluster.setCurrentWorkflowStep(
                    com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep.DESTROY);
            vmCluster.setDeletingStartedAt(LocalDateTime.now());
            vmClusterRepository.save(vmCluster);
            vmClusterAsyncService.destroyClusterAsync(clusterName);
            return HttpStatus.ACCEPTED;
        }

        cluster.ifPresent(clusterRepository::delete);
        vmClusterRepository.delete(vmCluster);
        return HttpStatus.OK;
    }

    private VmClusterEntity getVmCluster(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
    }
}
