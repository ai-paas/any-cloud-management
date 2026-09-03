package com.aipaas.anycloud.domain.provisioning.convergence.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.model.BootstrapInfo;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentComponentTest {

    private final ClusterService clusterService = mock(ClusterService.class);
    private final AgentApiManagedInstaller installer = mock(AgentApiManagedInstaller.class);
    private final VmClusterRemoteAccessService remoteAccess = mock(VmClusterRemoteAccessService.class);

    private AgentComponent component(Requirement configured) {
        return new AgentComponent(clusterService, installer, remoteAccess, configured);
    }

    private VmClusterEntity cluster() {
        VmClusterEntity vmCluster = new VmClusterEntity();
        vmCluster.setClusterName("demo-aws-01");
        return vmCluster;
    }

    private ClusterEntity clusterEntity(ClusterStatus status) {
        ClusterEntity entity = new ClusterEntity();
        entity.setStatus(status);
        return entity;
    }

    @Test
    void type_isAgent() {
        assertThat(component(Requirement.REQUIRED).type()).isEqualTo(ComponentType.AGENT);
    }

    @Test
    void requirement_comesFromConfiguration() {
        // agent 도달성은 배포 환경에 의존한다. 코드에 박으면 운영에서 되돌릴 수 없다.
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().build();
        assertThat(component(Requirement.BEST_EFFORT).requirementFor(spec)).isEqualTo(Requirement.BEST_EFFORT);
        assertThat(component(Requirement.REQUIRED).requirementFor(spec)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void probe_readyWhenClusterActive() {
        when(clusterService.getClusterEntity(anyString())).thenReturn(clusterEntity(ClusterStatus.ACTIVE));
        assertThat(component(Requirement.REQUIRED).probe(cluster(), Map.of()).health())
                .isEqualTo(ComponentHealth.READY);
    }

    @Test
    void probe_notReadyWhenAgentPending() {
        when(clusterService.getClusterEntity(anyString())).thenReturn(clusterEntity(ClusterStatus.AGENT_PENDING));
        var result = component(Requirement.REQUIRED).probe(cluster(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.NOT_READY);
        assertThat(result.detail()).contains("AGENT_PENDING");
    }

    @Test
    void probe_unknownWhenLookupFails() {
        when(clusterService.getClusterEntity(anyString())).thenThrow(new IllegalStateException("not found"));
        assertThat(component(Requirement.REQUIRED).probe(cluster(), Map.of()).health())
                .isEqualTo(ComponentHealth.UNKNOWN);
    }

    private BootstrapInfo bootstrapInfo() {
        return new BootstrapInfo("tok", "2026-09-03T11:00:00Z", "grpc.example:9090", "/url", "helm ...", "curl ...");
    }

    @Test
    void apply_appliesRenderedManifestOverSsh() {
        when(installer.prepareBootstrap(anyString())).thenReturn(bootstrapInfo());
        when(installer.renderManifest(anyString(), anyString())).thenReturn("apiVersion: v1\nkind: Namespace\n");
        org.mockito.ArgumentCaptor<String> captured = org.mockito.ArgumentCaptor.forClass(String.class);
        when(remoteAccess.runOnMaster(any(), any(), captured.capture(), any())).thenReturn("");

        component(Requirement.REQUIRED).apply(cluster(), Map.of());

        // manifest 의 따옴표와 개행이 SSH 인자 quoting 을 깨므로 base64 로 감싼다.
        assertThat(captured.getValue()).contains("base64 -d").contains("kubectl apply -f -");
    }

    @Test
    void apply_propagatesFailure() {
        when(installer.prepareBootstrap(anyString())).thenThrow(new IllegalStateException("token mint failed"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> component(Requirement.REQUIRED).apply(cluster(), Map.of()))
                .hasMessageContaining("token mint failed");
    }
}
