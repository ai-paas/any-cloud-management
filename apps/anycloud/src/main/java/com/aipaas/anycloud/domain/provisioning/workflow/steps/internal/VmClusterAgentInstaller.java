package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pulumi-provisioned cluster 에 ClusterAgent 를 SSH 로 자동 설치.
 *
 * <p>BOOTSTRAP step 의 부수 작업으로 분리됐다. AGENT path 는 agent 가 아직 없어 닭-달걀,
 * fabric8 path 는 kubeconfig 자격 저장이 제거된 뒤로 동작하지 않음. SSH 가 유일하게 보장된
 * transport. 실패해도 BOOTSTRAP 자체는 success — agent 없어도 cluster 운영 가능, 운영자가
 * {@code GET /v1/clusters/{id}/agent-manifest.yaml} 로 추후 manual install 가능.
 *
 * <p>⚠ agent 가 ACTIVE 로 전환되려면 backend gRPC endpoint
 * ({@code ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT}) 가 cluster 에서 도달 가능해야 한다 — dev 기본값
 * (host.docker.internal) 은 CSP VM 에서 도달 불가하므로 pod 는 뜨지만 dial-in 재시도 상태로 남는다.
 */
@Slf4j
@Component
class VmClusterAgentInstaller {

    private final AgentApiManagedInstaller agentApiManagedInstaller;
    private final VmClusterRemoteAccessService remoteAccessService;
    private final boolean enabled;

    VmClusterAgentInstaller(
            AgentApiManagedInstaller agentApiManagedInstaller,
            VmClusterRemoteAccessService remoteAccessService,
            @Value("${agent.api-managed.enabled:${ANYCLOUD_AGENT_API_MANAGED_ENABLED:true}}") boolean enabled) {
        this.agentApiManagedInstaller = agentApiManagedInstaller;
        this.remoteAccessService = remoteAccessService;
        this.enabled = enabled;
    }

    /**
     * Master 노드에 SSH 로 manifest 를 kubectl apply. 실패는 swallow + log — best-effort.
     */
    void installViaSsh(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        if (!enabled) {
            log.info("Cluster agent auto-install disabled (agent.api-managed.enabled=false) — skipping");
            return;
        }
        try {
            var bootstrap = agentApiManagedInstaller.prepareBootstrap(vmCluster.getClusterName());
            String manifest = agentApiManagedInstaller.renderManifest(vmCluster.getClusterName(), bootstrap.token());
            // Base64 wrap — SSH 명령 인자의 quoting 문제 (YAML 의 따옴표/개행) 회피.
            String b64 = Base64.getEncoder().encodeToString(manifest.getBytes(StandardCharsets.UTF_8));
            remoteAccessService.runOnMaster(
                    vmCluster,
                    outputs,
                    "echo '" + b64 + "' | base64 -d | sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl apply -f -",
                    Duration.ofMinutes(3));
            log.info(
                    "Cluster agent auto-install ok (SSH apply) cluster={} manifest_bytes={} backend_endpoint={}",
                    vmCluster.getClusterName(),
                    manifest.length(),
                    bootstrap.backendEndpoint());
        } catch (Exception e) {
            // agent 설치 실패가 BOOTSTRAP 자체를 실패시키지 않도록.
            log.warn(
                    "Cluster agent auto-install failed cluster={} (continuing without agent): {}",
                    vmCluster.getClusterName(),
                    e.toString());
        }
    }
}
