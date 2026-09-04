package com.aipaas.anycloud.domain.provisioning.convergence.components;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * cluster agent 의 dial-in 성립 여부를 ClusterEntity 상태로 판정.
 *
 * <p>등급이 설정값인 이유 — agent 가 ACTIVE 로 가려면 백엔드 gRPC endpoint 가 CSP VM 에서 도달
 * 가능해야 하는데, 개발 기본값(host.docker.internal)은 그렇지 않다. 운영에서는 REQUIRED 여야 하고
 * 개발에서는 아니어야 한다.
 */
@Component
public class AgentComponent implements ClusterComponent {

    private static final Duration APPLY_TIMEOUT = Duration.ofMinutes(3);

    private final ClusterService clusterService;
    private final AgentApiManagedInstaller agentApiManagedInstaller;
    private final VmClusterRemoteAccessService remoteAccess;
    private final Requirement configuredRequirement;

    public AgentComponent(
            ClusterService clusterService,
            AgentApiManagedInstaller agentApiManagedInstaller,
            VmClusterRemoteAccessService remoteAccess,
            @Value("${anycloud.vm-cluster.component.agent.requirement:REQUIRED}") Requirement configuredRequirement) {
        this.clusterService = clusterService;
        this.agentApiManagedInstaller = agentApiManagedInstaller;
        this.remoteAccess = remoteAccess;
        this.configuredRequirement = configuredRequirement;
    }

    /**
     * {@code VmClusterAgentInstaller} 에서 옮겨온 SSH 설치 경로.
     *
     * <p>AGENT transport 는 agent 가 아직 없어 쓸 수 없고, fabric8 경로는 kubeconfig 자격 저장이
     * 제거된 뒤로 동작하지 않는다. SSH 가 유일하게 보장된 transport 다.
     */
    @Override
    public void apply(VmClusterEntity cluster, Map<String, Object> outputs) {
        var bootstrap = agentApiManagedInstaller.prepareBootstrap(cluster.getClusterName());
        String manifest = agentApiManagedInstaller.renderManifest(cluster.getClusterName(), bootstrap.token());
        // manifest 의 따옴표와 개행이 SSH 인자 quoting 을 깨므로 base64 로 감싼다.
        String encoded = Base64.getEncoder().encodeToString(manifest.getBytes(StandardCharsets.UTF_8));
        remoteAccess.runOnMaster(
                cluster,
                outputs,
                "echo '" + encoded + "' | base64 -d | "
                        + "sudo KUBECONFIG=/etc/kubernetes/admin.conf kubectl apply -f -",
                APPLY_TIMEOUT);
    }

    @Override
    public ComponentType type() {
        return ComponentType.AGENT;
    }

    @Override
    public Requirement requirementFor(VmClusterInternalRequestSnapshot spec) {
        return configuredRequirement;
    }

    @Override
    public ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs) {
        try {
            ClusterStatus status =
                    clusterService.getClusterEntity(cluster.getClusterName()).getStatus();
            return status == ClusterStatus.ACTIVE
                    ? ComponentProbe.ready()
                    : ComponentProbe.notReady("agent 미연결 (cluster status=" + status + ")");
        } catch (Exception e) {
            return ComponentProbe.unknown("cluster 조회 실패: " + e.getMessage());
        }
    }
}
