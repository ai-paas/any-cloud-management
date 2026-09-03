package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.PermanentProvisioningFailure;
import com.aipaas.anycloud.common.error.exception.provisioning.TransientProvisioningFailure;
import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.operation.OperationProgressTracker;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterNodeLabelService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterScaleDrainService;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * VM cluster scale 작업 step service. Pulumi {@code up} 의 idempotency 활용 — 새 workerCount 로
 * {@link ProvisioningRequest} 재구성 → {@link ProvisioningService#provision(ProvisioningRequest)}
 * 호출. master 는 변경 없고 worker 수만 reconcile. drain (scale-down) + label reconcile 동봉.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterScaleStepService {

    private final VmClusterRepository vmClusterRepository;
    private final ProvisioningService provisioningService;
    private final CspCredentialService cspCredentialService;
    private final VmClusterPayloadService vmClusterPayloadService;
    private final VmClusterScaleDrainService scaleDrainService;
    private final VmClusterNodeLabelService nodeLabelService;
    private final OperationProgressTracker progress;

    @Async(AsyncConfig.PROVISIONING_EXECUTOR)
    public CompletableFuture<Void> scaleClusterAsync(String clusterName, int workerCount) {
        // Scale 호출은 RabbitMQ 가 아닌 직접 @Async 진입. listener 와 동일한 컨텍스트 가시성을
        // 위해 MDC 를 수동 설정.
        try (var ignored = LoggingMdc.scope(Map.of(LoggingMdc.CLUSTER_NAME, clusterName, LoggingMdc.STEP, "SCALE"))) {
            return doScale(clusterName, workerCount);
        }
    }

    private CompletableFuture<Void> doScale(String clusterName, int workerCount) {
        VmClusterEntity vmCluster = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));

        // status 일시적으로 SCALING 으로 — workflow 가드와 충돌하지 않게 명시 전환.
        vmCluster.transitionTo(VmClusterStatus.SCALING, "scale.start");
        vmCluster.setLastError(null);
        vmCluster.setFailedAt(null);
        vmClusterRepository.save(vmCluster);

        try {
            ResolvedCspCredential credential = cspCredentialService.resolveForProvision(
                    vmCluster.getClusterProvider(), vmCluster.getCredentialId());
            ProvisioningRequest baseRequest = vmClusterPayloadService.restoreProvisioningRequest(vmCluster, credential);
            if (baseRequest == null) {
                throw new PermanentProvisioningFailure(
                        "Failed to restore ProvisioningRequest for scale (cluster=" + clusterName + ")",
                        ErrorCode.PROVISIONING_REQUEST_MISSING);
            }

            // 새 worker count 를 config 에 주입 — Pulumi program 이 ctx.config("anycloud-k8s:workerCount") 로 read.
            Map<String, String> newConfig = new LinkedHashMap<>(baseRequest.configOrEmpty());
            newConfig.put("anycloud-k8s:workerCount", String.valueOf(workerCount));
            ProvisioningRequest scaleRequest = ProvisioningRequest.builder()
                    .provider(baseRequest.getProvider())
                    .clusterName(baseRequest.getClusterName())
                    .environment(baseRequest.getEnvironment())
                    .region(baseRequest.getRegion())
                    .credentialId(baseRequest.getCredentialId())
                    .credentialName(baseRequest.getCredentialName())
                    .config(newConfig)
                    .credentialEnvironment(baseRequest.credentialEnvironmentOrEmpty())
                    .build();

            // scale-down 감지 — 현 outputs 의 worker 수와 새 workerCount 비교 후 K8s drain.
            Map<String, Object> currentOutputs = provisioningService.stackOutputs(
                    vmCluster.getStackName(), true, scaleRequest.credentialEnvironmentOrEmpty());
            int currentWorkerCount = countCurrentWorkers(currentOutputs);
            if (workerCount < currentWorkerCount) {
                int toRemove = currentWorkerCount - workerCount;
                log.info(
                        "Scale-down on cluster {}: {} → {} workers, draining {} node(s) first",
                        clusterName,
                        currentWorkerCount,
                        workerCount,
                        toRemove);
                scaleDrainService.drainExcessWorkers(vmCluster, currentOutputs, toRemove);
            }
            progress.updateProgress("cluster", clusterName, "DRAIN_WORKERS", 1, 50);

            // Automation API up — idempotent. master 변경 없고 worker 수만 reconcile.
            Map<String, Object> outputs = provisioningService.provision(scaleRequest);
            progress.updateProgress("cluster", clusterName, "PULUMI_APPLY", 2, 90);

            vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
            vmCluster.transitionTo(VmClusterStatus.READY, "scale.ok");
            vmClusterRepository.save(vmCluster);
            // scale-up 직후에도 pulumi-index 라벨을 새 워커들에 부착해 다음 drain 시 정확도 확보.
            // scale-down 경우 drainExcessWorkers 가 이미 reconcile 했지만 idempotent.
            nodeLabelService.reconcilePulumiIndexLabels(vmCluster, outputs);
            log.info("Scaled VM cluster {} to workerCount={}", clusterName, workerCount);
            progress.complete("cluster", clusterName, "{\"workerCount\":" + workerCount + "}");
        } catch (Exception e) {
            log.error("Failed to scale VM cluster {} to workerCount={}: {}", clusterName, workerCount, e.toString());
            vmCluster.transitionTo(VmClusterStatus.FAILED, "scale.fail");
            vmCluster.setLastError("scale failed: " + e.getMessage());
            vmCluster.setFailedAt(LocalDateTime.now());
            vmClusterRepository.save(vmCluster);
            progress.fail("cluster", clusterName, "scale failed: " + e.getMessage());
            if (e instanceof RuntimeException re) {
                // PermanentProvisioningFailure / TransientProvisioningFailure 등은 그대로 통과.
                throw re;
            }
            throw new TransientProvisioningFailure("scale failed: " + e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    private int countCurrentWorkers(Map<String, Object> outputs) {
        Object nodes = outputs == null ? null : outputs.get("nodes");
        if (!(nodes instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> node) {
                Object role = node.get("role");
                if (role != null && String.valueOf(role).toLowerCase().startsWith("worker")) {
                    n++;
                }
            }
        }
        return n;
    }
}
