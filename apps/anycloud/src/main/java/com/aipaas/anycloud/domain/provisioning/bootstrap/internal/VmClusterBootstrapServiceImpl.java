package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter.BootstrapSubStep;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapService;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategyResolver;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VmClusterBootstrapServiceImpl implements VmClusterBootstrapService {

    private static final Duration CLOUD_INIT_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration MASTER_BOOTSTRAP_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration WORKER_JOIN_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration NODE_READY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration ADDON_TIMEOUT = Duration.ofMinutes(15);
    private static final int PREPARATION_ATTEMPTS = 3;
    private static final int MASTER_INIT_ATTEMPTS = 2;
    private static final int WORKER_JOIN_ATTEMPTS = 2;
    private static final int NODE_READY_ATTEMPTS = 3;
    private static final int ADDON_ATTEMPTS = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    private final VmClusterRemoteAccessService vmClusterRemoteAccessService;
    private final VmClusterBootstrapStrategyResolver strategyResolver;
    private final VmClusterBootstrapSnapshotService snapshotService;
    private final VmClusterNodeResolver nodeResolver;
    private final VmClusterBootstrapProgressReporter progressReporter;

    @Override
    // Bootstrap runs in a strict order so failures are easier to localize in worker logs and VM status.
    public void bootstrap(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        VmClusterInternalRequestSnapshot snapshot = snapshotService.read(vmCluster.getRequestConfig());
        VmClusterBootstrapStrategy strategy = strategyResolver.resolve(vmCluster.getClusterProvider());
        String clusterName = vmCluster.getClusterName();

        progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.NODE_PREPARATION);
        waitForNodePreparation(vmCluster, outputs, strategy);

        progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.MASTER_INIT);
        initializeMaster(vmCluster, outputs, snapshot, strategy);

        // HA mode — extra master 들을 control-plane 으로 join. single-master 면 no-op + report 도 skip.
        if (!nodeResolver.extraMasterHosts(outputs).isEmpty()) {
            progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.EXTRA_MASTER_JOIN);
            joinExtraMasters(vmCluster, outputs, snapshot, strategy);
        }

        progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.WORKER_JOIN);
        joinWorkers(vmCluster, outputs, snapshot, strategy);

        //  ADDONS (CNI 포함) 가 NODES_READY 보다 먼저여야 한다 — CNI 없는 노드는 영원히
        // NotReady 라 Ready 대기가 10분 timeout 으로 확정 실패 (e2e 실증). addon 명령 내부의
        // ingress/GPU wait 는 `|| true` 라 NotReady 상태에서 시작해도 안전.
        progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.ADDONS);
        installAddons(vmCluster, outputs, snapshot, strategy);

        progressReporter.reportSubStepStart(clusterName, BootstrapSubStep.NODES_READY);
        waitForNodesReady(vmCluster, outputs, strategy);
    }

    /**
     * HA control-plane join: extra master 들이 lead master 의 init 결과 (cert key + token + CA
     * hash) 를 받아 {@code kubeadm join --control-plane} 수행. lead master IP / token / cert key
     * 가 필요. single-master 면 no-op.
     */
    private void joinExtraMasters(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            VmClusterInternalRequestSnapshot snapshot,
            VmClusterBootstrapStrategy strategy) {
        List<String> extras = nodeResolver.extraMasterHosts(outputs);
        if (extras.isEmpty()) {
            return; // single-master cluster
        }

        String leadPrivateIp = nodeResolver.masterPrivateIp(outputs);
        String caHash = runOnMasterWithRetry(
                        vmCluster,
                        outputs,
                        strategy.resolveCaHashCommand(),
                        Duration.ofMinutes(2),
                        MASTER_INIT_ATTEMPTS,
                        "CA hash resolution for HA")
                .trim();
        String certificateKey = runOnMasterWithRetry(
                        vmCluster,
                        outputs,
                        strategy.uploadCertsCommand(),
                        Duration.ofMinutes(2),
                        MASTER_INIT_ATTEMPTS,
                        "upload-certs key generation")
                .trim();

        for (String extra : extras) {
            runOnHostWithRetry(
                    vmCluster,
                    outputs,
                    extra,
                    strategy.buildControlPlaneJoinCommand(snapshot, leadPrivateIp, caHash, certificateKey),
                    MASTER_BOOTSTRAP_TIMEOUT,
                    MASTER_INIT_ATTEMPTS,
                    "extra master join (control-plane)");
        }
    }

    private void waitForNodePreparation(
            VmClusterEntity vmCluster, Map<String, Object> outputs, VmClusterBootstrapStrategy strategy) {
        // Every node must finish base package preparation before kubeadm commands start.
        for (VmClusterNodeResolver.VmClusterNode node : nodeResolver.readNodes(outputs)) {
            runOnHostWithRetry(
                    vmCluster,
                    outputs,
                    node.host(),
                    strategy.waitForPreparationCommand(),
                    CLOUD_INIT_TIMEOUT,
                    PREPARATION_ATTEMPTS,
                    "node preparation");
        }
    }

    private void initializeMaster(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            VmClusterInternalRequestSnapshot snapshot,
            VmClusterBootstrapStrategy strategy) {
        // Master init is the source of join metadata, so it is always executed before any worker step.
        runOnMasterWithRetry(
                vmCluster,
                outputs,
                strategy.initializeMasterCommand(snapshot),
                MASTER_BOOTSTRAP_TIMEOUT,
                MASTER_INIT_ATTEMPTS,
                "master initialization");
    }

    private void joinWorkers(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            VmClusterInternalRequestSnapshot snapshot,
            VmClusterBootstrapStrategy strategy) {
        List<VmClusterNodeResolver.VmClusterNode> workers = nodeResolver.readNodes(outputs).stream()
                .filter(node -> !"master".equalsIgnoreCase(node.role()))
                .toList();
        if (workers.isEmpty()) {
            return;
        }

        String masterPrivateIp = nodeResolver.masterPrivateIp(outputs);
        String caHash = runOnMasterWithRetry(
                        vmCluster,
                        outputs,
                        strategy.resolveCaHashCommand(),
                        Duration.ofMinutes(2),
                        WORKER_JOIN_ATTEMPTS,
                        "cluster CA hash resolution")
                .trim();

        // Workers join one by one so the failing node is visible without additional correlation.
        for (VmClusterNodeResolver.VmClusterNode worker : workers) {
            runOnHostWithRetry(
                    vmCluster,
                    outputs,
                    worker.host(),
                    strategy.buildWorkerJoinCommand(snapshot, masterPrivateIp, caHash),
                    WORKER_JOIN_TIMEOUT,
                    WORKER_JOIN_ATTEMPTS,
                    "worker join");
        }
    }

    private void waitForNodesReady(
            VmClusterEntity vmCluster, Map<String, Object> outputs, VmClusterBootstrapStrategy strategy) {
        // Readiness waits on the control plane and workers before optional add-ons are installed.
        runOnMasterWithRetry(
                vmCluster,
                outputs,
                strategy.waitForClusterReadyCommand(),
                NODE_READY_TIMEOUT,
                NODE_READY_ATTEMPTS,
                "cluster readiness wait");
    }

    private void installAddons(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            VmClusterInternalRequestSnapshot snapshot,
            VmClusterBootstrapStrategy strategy) {
        String command = strategy.buildAddonInstallCommand(snapshot);
        if (command == null || command.isBlank()) {
            return;
        }

        runOnMasterWithRetry(vmCluster, outputs, command, ADDON_TIMEOUT, ADDON_ATTEMPTS, "addon installation");
    }

    private String runOnMasterWithRetry(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            String command,
            Duration timeout,
            int maxAttempts,
            String stepDescription) {
        String host = nodeResolver.masterHost(outputs);
        return runOnHostWithRetry(vmCluster, outputs, host, command, timeout, maxAttempts, stepDescription);
    }

    private String runOnHostWithRetry(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            String host,
            String command,
            Duration timeout,
            int maxAttempts,
            String stepDescription) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return vmClusterRemoteAccessService.runOnHost(vmCluster, outputs, host, command, timeout);
            } catch (Exception e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    break;
                }
                sleepBeforeRetry();
            }
        }
        throw new IllegalStateException(
                "Failed during " + stepDescription + " on host " + host + " after " + maxAttempts + " attempts",
                lastException);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bootstrap retry interrupted", e);
        }
    }
}
