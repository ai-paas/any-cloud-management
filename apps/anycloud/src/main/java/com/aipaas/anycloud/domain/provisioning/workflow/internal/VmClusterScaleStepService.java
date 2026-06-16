package com.aipaas.anycloud.domain.provisioning.workflow.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.CspStderrClassifier;
import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.operation.OperationProgressTracker;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterNodeLabelService;
import com.aipaas.anycloud.domain.provisioning.scale.VmClusterScaleDrainService;
import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import io.aipaas.cluster.provisioning.service.PulumiCommandService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * VM cluster scale 작업 step service — pulumi config 변경 + drain + apply + label reconcile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterScaleStepService {

    private static final Duration SCALE_UP_TIMEOUT = Duration.ofMinutes(20);

    private final VmClusterRepository vmClusterRepository;
    private final PulumiCommandService pulumiCommandService;
    private final io.aipaas.cluster.provisioning.service.PulumiStaleLockGuard staleLockGuard;
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

        // status 일시적으로 PROVISIONING 으로 — workflow 가드와 충돌하지 않게 명시 전환.
        // workflow_retry_count 는 건드리지 않음 (실패해도 retry 임계 영향 없음).
        vmCluster.transitionTo(VmClusterStatus.SCALING, "scale.start");
        vmCluster.setLastError(null);
        vmCluster.setFailedAt(null);
        vmClusterRepository.save(vmCluster);

        try {
            //  raw CSP env (AWS_ACCESS_KEY_ID 등) 를 그대로 넘기면 state backend (RustFS)
            // 자격증명을 덮어써 InvalidAccessKeyId — provision/destroy 와 동일하게 strip.
            // CSP 자격증명은 provision 시 stack config 로 영속화되어 있어 Pulumi 가 자동 사용.
            Map<String, String> environment =
                    io.aipaas.cluster.provisioning.service.CspCredentialPulumiConfigMapper.stripCspEnv(
                            cspCredentialService.resolveEnvironment(
                                    vmCluster.getClusterProvider(),
                                    vmCluster.getCredentialId(),
                                    vmCluster.getCredentialSourceType()));

            PulumiCommandResult select = pulumiCommandService.selectStack(vmCluster.getStackName(), environment);
            if (!select.isSuccess()) {
                throw pulumiFailure("stack select", select);
            }

            // scale-down 감지: 현재 outputs 에서 worker 수를 추출해 새 workerCount 와 비교.
            // 줄어드는 경우 사라질 노드들을 K8s 측에서 사전 drain (Day-2 §1 후속 #2).
            Map<String, Object> currentOutputs = pulumiCommandService.stackOutputs(true, environment);
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
            // W2: SCALE 의 step 1/2 (drain) 완료.
            progress.updateProgress("cluster", clusterName, "DRAIN_WORKERS", 1, 50);

            PulumiCommandResult setCfg = pulumiCommandService.setConfig(
                    "anycloud-k8s:workerCount", String.valueOf(workerCount), false, environment);
            if (!setCfg.isSuccess()) {
                throw pulumiFailure("config set", setCfg);
            }

            //  stale lock 자동 복구 — provision/destroy 와 동일한 guard 적용.
            PulumiCommandResult up = staleLockGuard.run(
                    vmCluster.getStackName(),
                    environment,
                    () -> pulumiCommandService.run(
                            List.of("up", "--yes", "--skip-preview"), SCALE_UP_TIMEOUT, environment));
            if (!up.isSuccess()) {
                throw pulumiFailure("up", up);
            }

            // W2: SCALE 의 step 2/2 (pulumi apply) 완료 직전 — 90% (실제 outputs 수집 + 라벨 reconcile 전).
            progress.updateProgress("cluster", clusterName, "PULUMI_APPLY", 2, 90);

            Map<String, Object> outputs = pulumiCommandService.stackOutputs(true, environment);
            vmCluster.setRawOutputs(vmClusterPayloadService.serializeSanitizedOutputs(outputs));
            vmCluster.transitionTo(VmClusterStatus.READY, "scale.ok");
            vmClusterRepository.save(vmCluster);
            // scale-up 직후에도 pulumi-index 라벨을 새 워커들에 부착해 다음 drain 시 정확도 확보.
            // scale-down 의 경우 drainExcessWorkers 가 이미 reconcile 을 수행했지만 한번 더 호출해도 idempotent.
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
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Pulumi 실패를 분류 — stderr 가 비면 stdout, 그래도 비면 exit code 를 detail 로 사용.
     * (비-JSON pulumi 는 진단을 stdout 에 출력하므로 stderr 만 보면 원인을 잃는다.)
     */
    private static com.aipaas.anycloud.common.error.exception.provisioning.ProvisioningException pulumiFailure(
            String action, PulumiCommandResult result) {
        return CspStderrClassifier.classifyPulumi(
                action, result.getStderr(), result.getStdout(), "exit code " + result.getExitCode());
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
