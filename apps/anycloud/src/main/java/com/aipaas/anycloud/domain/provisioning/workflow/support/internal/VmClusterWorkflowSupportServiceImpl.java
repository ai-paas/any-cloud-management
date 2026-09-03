package com.aipaas.anycloud.domain.provisioning.workflow.support.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapLogService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterWorkflowProperties;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowMessage;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import com.aipaas.anycloud.domain.webhook.WebhookEvent;
import com.aipaas.anycloud.domain.webhook.WebhookEventPublisher;
import com.aipaas.anycloud.domain.webhook.WebhookEventTypes;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Workflow 단계 별 상태 전이 + 외부 통보. 9 dependency 를 4 책임 group 으로 정리 :
 *
 * <ol>
 *   <li>Repository / lookup — {@code vmClusterRepository}, {@code workflowProperties}.</li>
 *   <li>State transition — {@code markStepStarted / markStepSucceeded / markReady /
 *       markDeleteCompleted / fail / failWithDiagnostics}. entity field 직접 mutate.</li>
 *   <li>Diagnostics collection — {@code cspCredentialService} + {@code pulumiProvisioningService}
 *       + {@code vmClusterPayloadService} + {@code vmClusterBootstrapLogService}. failWithDiagnostics
 *       전용 — 향후 별도 {@code WorkflowDiagnosticsCollector} 로 추출 가능.</li>
 *   <li>External publish — {@code operationService} (operation row 갱신), {@code workflowPublisher}
 *       (DESTROY 자동 cleanup), {@code webhookEventPublisher} (외부 system 통보). 향후
 *       {@code WorkflowEventPublisher} facade 로 묶을 가능.</li>
 * </ol>
 *
 * <p>현재는 책임 묶음을 // ─── ... ─── 섹션 주석으로 표시. 진정한 클래스 분해는 별도 PR — caller
 * 가 본 service 의 public 메서드만 호출하므로 internal 재구성은 호환성 유지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterWorkflowSupportServiceImpl implements VmClusterWorkflowSupportService {

    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final ProvisioningService provisioningService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final VmClusterBootstrapLogService vmClusterBootstrapLogService;
    private final VmClusterWorkflowProperties workflowProperties;
    private final VmClusterWorkflowPublisher workflowPublisher;
    private final WebhookEventPublisher webhookEventPublisher;
    private final OperationService operationService;

    @Override
    public VmClusterEntity getVmClusterById(String vmClusterId, String clusterName) {
        return vmClusterRepository.findById(vmClusterId).orElseThrow(() -> new ClusterNotFoundException(clusterName));
    }

    @Override
    public VmClusterEntity getLatestVmCluster(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
    }

    @Override
    public void markStepStarted(
            VmClusterEntity vmCluster,
            VmClusterWorkflowStep step,
            VmClusterStatus status,
            boolean incrementRetryCount) {
        LocalDateTime now = LocalDateTime.now();
        vmCluster.transitionTo(status, "workflow.step." + (step == null ? "?" : step.name()));
        vmCluster.setCurrentWorkflowStep(step);
        vmCluster.setLastError(null);
        vmCluster.setFailedAt(null);
        vmCluster.setLastFailedStep(null);
        if (incrementRetryCount) {
            vmCluster.setWorkflowRetryCount(safeRetryCount(vmCluster) + 1);
        }
        switch (step) {
            case PROVISION -> {
                if (vmCluster.getRequestedAt() == null) {
                    vmCluster.setRequestedAt(now);
                }
                vmCluster.setProvisioningStartedAt(now);
            }
            case BOOTSTRAP -> vmCluster.setBootstrappingStartedAt(now);
            case VERIFY -> vmCluster.setVerifyingStartedAt(now);
            case DESTROY -> vmCluster.setDeletingStartedAt(now);
        }
        vmClusterRepository.save(vmCluster);

        // Operation 진행률 push — ClusterFacadeImpl 가 미리 start() 한 op 가 있으면 진행 단계 갱신.
        updateActiveOperationProgress(vmCluster.getClusterName(), step);
    }

    @Override
    public void markStepSucceeded(VmClusterEntity vmCluster, VmClusterWorkflowStep step) {
        vmCluster.setLastSuccessfulStep(step);
        vmClusterRepository.save(vmCluster);
    }

    @Override
    public void markReady(VmClusterEntity vmCluster) {
        vmCluster.transitionTo(VmClusterStatus.READY, "workflow.ready");
        vmCluster.setCurrentWorkflowStep(VmClusterWorkflowStep.VERIFY);
        vmCluster.setLastSuccessfulStep(VmClusterWorkflowStep.VERIFY);
        vmCluster.setClusterRegistered(true);
        vmCluster.setLastError(null);
        vmCluster.setLastErrorCode(null);
        vmCluster.setCurrentSubStep(null);
        vmCluster.setSubStepStartedAt(null);
        vmCluster.setFailedAt(null);
        vmCluster.setReadyAt(LocalDateTime.now());
        vmClusterRepository.save(vmCluster);
        completeActiveOperation(vmCluster.getClusterName(), "READY");
        publishWebhook(WebhookEventTypes.VM_CLUSTER_READY, vmCluster, null);
    }

    @Override
    public void markDegraded(VmClusterEntity vmCluster) {
        vmCluster.transitionTo(VmClusterStatus.DEGRADED, "workflow.degraded");
        vmCluster.setCurrentWorkflowStep(VmClusterWorkflowStep.VERIFY);
        vmCluster.setLastSuccessfulStep(VmClusterWorkflowStep.VERIFY);
        vmCluster.setClusterRegistered(true);
        vmCluster.setCurrentSubStep(null);
        vmCluster.setSubStepStartedAt(null);
        // readyAt 은 채우지 않는다 — 아직 요청한 구성이 갖춰지지 않았다.
        // failedAt 도 아니다. 워크플로우가 실패한 게 아니라 수렴을 기다리는 상태다.
        vmClusterRepository.save(vmCluster);
    }

    @Override
    public void markDeleteCompleted(VmClusterEntity vmCluster) {
        vmCluster.transitionTo(VmClusterStatus.DELETED, "workflow.deleted");
        vmCluster.setCurrentWorkflowStep(VmClusterWorkflowStep.DESTROY);
        vmCluster.setLastSuccessfulStep(VmClusterWorkflowStep.DESTROY);
        vmCluster.setActiveRequestKey(null);
        vmCluster.setClusterRegistered(false);
        vmCluster.setLastError(null);
        vmCluster.setDeletedAt(LocalDateTime.now());
        // DELETED 후에도 row 는 audit history 로 보존되므로 sensitive 페이로드 (CSP credential / SSH private
        // key / passphrase / kubeconfig 등) 는 정리. metadata (status/timestamps/cluster_name 등) 만 보존.
        vmCluster.setRequestConfig(null);
        vmCluster.setRawOutputs(null);
        vmCluster.setBootstrapLog(null);
        vmClusterRepository.save(vmCluster);
        completeActiveOperation(vmCluster.getClusterName(), "DELETED");
        publishWebhook(WebhookEventTypes.VM_CLUSTER_DELETED, vmCluster, null);
    }

    @Override
    public void fail(VmClusterEntity vmCluster, String clusterName, Exception e) {
        log.error("VM cluster workflow failed for cluster {}: {}", clusterName, e.getMessage(), e);
        // 재시도 임계 초과 시 자동 진행 정지 (manual intervention 대기).
        // retry count 가 maxAttempts 이상이면 BLOCKED, 아니면 통상 FAILED 로 분류.
        int retryCount = safeRetryCount(vmCluster);
        int maxAttempts = workflowProperties.getMaxAttempts();
        if (retryCount >= maxAttempts) {
            log.warn(
                    "VM cluster {} reached retry limit ({}/{}). Switching to BLOCKED (manual intervention required).",
                    clusterName,
                    retryCount,
                    maxAttempts);
            vmCluster.transitionTo(VmClusterStatus.BLOCKED, "workflow.blocked");
            vmCluster.setLastError(String.format(
                    "Manual intervention required after %d/%d retries: %s", retryCount, maxAttempts, e.getMessage()));
        } else {
            vmCluster.transitionTo(VmClusterStatus.FAILED, "workflow.fail");
            vmCluster.setLastError(e.getMessage());
        }
        vmCluster.setActiveRequestKey(null);
        vmCluster.setFailedAt(LocalDateTime.now());
        //  실패 분류 코드 기록 — ErrorResponse.code 와 동일 체계로 UI 가 메시지 대신 분기 가능.
        vmCluster.setLastErrorCode(resolveErrorCode(e));
        VmClusterWorkflowStep failedStep = vmCluster.getCurrentWorkflowStep();
        if (failedStep != null) {
            vmCluster.setLastFailedStep(failedStep);
        }
        vmClusterRepository.save(vmCluster);

        // Operation 실패 마킹. retry 진행 중(FAILED) 이라도 운영자가 op 를 보고 있을 수 있으므로 fail 호출.
        failActiveOperation(clusterName, e.getMessage());

        // FAILED 와 BLOCKED 는 외부 포털 입장에서 서로 다른 알림 — 별도 event type 으로 발행.
        String eventType = vmCluster.getProvisioningStatus() == VmClusterStatus.BLOCKED
                ? WebhookEventTypes.VM_CLUSTER_BLOCKED
                : WebhookEventTypes.VM_CLUSTER_FAILED;
        publishWebhook(eventType, vmCluster, e.getMessage());

        // 부분 PROVISION cleanup. BOOTSTRAP/VERIFY 단계 실패는 cluster 가 살아있는 상태라
        // 자동 destroy 위험 — PROVISION 만 대상. retryCount 임계 도달 (BLOCKED 전환) 시점에만
        // trigger — 매 시도마다 destroy 폭주 방지.
        if (workflowProperties.isAutoCleanupOnProvisionFailure()
                && failedStep == VmClusterWorkflowStep.PROVISION
                && vmCluster.getProvisioningStatus() == VmClusterStatus.BLOCKED
                && vmCluster.getStackName() != null
                && !vmCluster.getStackName().isBlank()) {
            log.warn("Auto-cleanup: PROVISION exhausted retries on cluster {} → publishing DESTROY", clusterName);
            triggerAutoCleanup(vmCluster, clusterName);
        }
    }

    private void triggerAutoCleanup(VmClusterEntity vmCluster, String clusterName) {
        try {
            vmCluster.transitionTo(VmClusterStatus.DELETING, "workflow.deleting");
            vmCluster.setLastError("Auto-cleanup after PROVISION failure: " + vmCluster.getLastError());
            vmClusterRepository.save(vmCluster);
            workflowPublisher.publishDestroy(VmClusterWorkflowMessage.builder()
                    .vmClusterId(vmCluster.getId())
                    .clusterName(clusterName)
                    .stackName(vmCluster.getStackName())
                    .step(VmClusterWorkflowStep.DESTROY)
                    .build());
        } catch (Exception cleanupErr) {
            // cleanup publish 실패해도 fail() 의 결과는 유지 (BLOCKED 그대로 둠).
            log.error(
                    "Auto-cleanup publish failed for cluster {}: {} — leaving BLOCKED for manual intervention.",
                    clusterName,
                    cleanupErr.toString());
            vmCluster.transitionTo(VmClusterStatus.BLOCKED, "workflow.blocked");
            vmCluster.setLastError(vmCluster.getLastError() + " (auto-cleanup also failed)");
            vmClusterRepository.save(vmCluster);
        }
    }

    @Override
    public void failWithDiagnostics(VmClusterEntity vmCluster, String clusterName, Exception e) {
        try {
            Map<String, String> credentialEnvironment = cspCredentialService.resolveEnvironment(
                    vmCluster.getClusterProvider(), vmCluster.getCredentialId());
            Map<String, Object> outputs =
                    provisioningService.stackOutputs(vmCluster.getStackName(), true, credentialEnvironment);
            // Append 모드 — 이전 attempt 의 log 를 유지한 채 진단을 끝에 추가.
            vmClusterBootstrapLogService.appendDiagnostics(vmCluster, outputs);
            vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
        } catch (Exception logError) {
            log.warn("Failed to collect bootstrap diagnostics for cluster {}: {}", clusterName, logError.getMessage());
        }
        fail(vmCluster, clusterName, e);
    }

    private int safeRetryCount(VmClusterEntity vmCluster) {
        return vmCluster.getWorkflowRetryCount() == null ? 0 : vmCluster.getWorkflowRetryCount();
    }

    /**
     * 예외에서 분류 코드 추론. CustomException 의 ErrorCode 를 cause chain 따라 우선 사용 —
     * step 핸들러가 원본 예외를 감싸 던지는 경우 대비. 없으면 IllegalState=STATE_CONFLICT,
     * 그 외엔 예외 클래스 simple name 으로 fallback.
     */
    private static String resolveErrorCode(Exception e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof com.aipaas.anycloud.common.error.exception.CustomException ce
                    && ce.getErrorCode() != null) {
                return ce.getErrorCode().name();
            }
            c = c.getCause();
        }
        if (e instanceof IllegalStateException) {
            return com.aipaas.anycloud.common.error.enums.ErrorCode.STATE_CONFLICT.name();
        }
        return e == null ? null : e.getClass().getSimpleName();
    }

    // =================== Operation resource 통합 ===================
    // ClusterFacadeImpl 이 cluster 작업 시작 시 Operation row 를 미리 만든다.
    // 워크플로우 단계 핸들러는 그 row 를 진행 단계에 따라 갱신.
    // 동시 op 가 있을 수 있으나 보통은 1 개 — 가장 최근 active 1 개로 향한다(best-effort).

    private static int percentForStep(VmClusterWorkflowStep step) {
        if (step == null) return 0;
        return switch (step) {
            case PROVISION -> 33;
            case BOOTSTRAP -> 66;
            case VERIFY -> 90;
            case DESTROY -> 50;
        };
    }

    private static int stepIndexFor(VmClusterWorkflowStep step) {
        if (step == null) return 0;
        return switch (step) {
            case PROVISION -> 1;
            case BOOTSTRAP -> 2;
            case VERIFY -> 3;
            case DESTROY -> 1;
        };
    }

    private void updateActiveOperationProgress(String clusterName, VmClusterWorkflowStep step) {
        try {
            operationService.findLatestActiveByResource("cluster", clusterName).ifPresent(op -> {
                // PENDING 이면 RUNNING 으로 전환.
                if (op.getState() == com.aipaas.anycloud.domain.operation.model.OperationState.PENDING) {
                    operationService.markRunning(op.getId());
                }
                operationService.updateProgress(op.getId(), step.name(), stepIndexFor(step), percentForStep(step));
            });
        } catch (Exception ex) {
            log.warn("Operation progress update skipped (cluster {}, step {}): {}", clusterName, step, ex.toString());
        }
    }

    private void completeActiveOperation(String clusterName, String resultLabel) {
        try {
            operationService
                    .findLatestActiveByResource("cluster", clusterName)
                    .ifPresent(op -> operationService.complete(
                            op.getId(), resultLabel == null ? null : "{\"result\":\"" + resultLabel + "\"}"));
        } catch (Exception ex) {
            log.warn("Operation complete skipped (cluster {}): {}", clusterName, ex.toString());
        }
    }

    private void failActiveOperation(String clusterName, String errorMessage) {
        try {
            operationService
                    .findLatestActiveByResource("cluster", clusterName)
                    .ifPresent(op -> operationService.fail(op.getId(), errorMessage));
        } catch (Exception ex) {
            log.warn("Operation fail mark skipped (cluster {}): {}", clusterName, ex.toString());
        }
    }

    /**
     * Webhook event publish. async + failure-isolated — workflow 의 핵심 상태 전이 후 호출되며,
     * 외부 system 의 응답성과 무관하게 트랜잭션이 진행되.
     */
    private void publishWebhook(String eventType, VmClusterEntity vmCluster, String errorMessage) {
        try {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("clusterName", vmCluster.getClusterName());
            data.put("provisioningId", vmCluster.getId());
            data.put("provider", vmCluster.getClusterProvider());
            data.put("region", vmCluster.getRegion());
            data.put("environment", vmCluster.getEnvironment());
            data.put(
                    "status",
                    vmCluster.getProvisioningStatus() == null
                            ? null
                            : vmCluster.getProvisioningStatus().name());
            if (vmCluster.getStackName() != null) {
                data.put("stackName", vmCluster.getStackName());
            }
            if (errorMessage != null) {
                data.put("error", errorMessage);
            }
            // v1 API envelope 와 일관성을 위해 links 첨부. 수신측이 후속 호출 URL 을 자체 조립할 필요 없게.
            Map<String, String> links = new java.util.LinkedHashMap<>();
            String name = vmCluster.getClusterName();
            if (name != null && !name.isBlank()) {
                links.put("resource", "/v1/clusters/" + name);
                links.put("events", "/v1/clusters/" + name + "/events");
                links.put("operations", "/v1/clusters/" + name + "/operations");
            }
            webhookEventPublisher.publish(WebhookEvent.of(eventType, data).withLinks(links.isEmpty() ? null : links));
        } catch (Exception webhookErr) {
            log.warn(
                    "Webhook publish skipped due to publisher error: type={}, cluster={}, err={}",
                    eventType,
                    vmCluster.getClusterName(),
                    webhookErr.toString());
        }
    }
}
