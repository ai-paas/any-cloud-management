package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClusterComponentObserverImplTest {

    private final VmClusterComponentRepository repository = mock(VmClusterComponentRepository.class);
    private final VmClusterBootstrapSnapshotService snapshotService = mock(VmClusterBootstrapSnapshotService.class);
    private final ProvisioningService provisioningService = mock(ProvisioningService.class);

    private VmClusterEntity cluster() {
        VmClusterEntity vmCluster = new VmClusterEntity();
        vmCluster.setId("vmc-001");
        vmCluster.setClusterName("demo-aws-01");
        vmCluster.setStackName("demo-aws-01-dev");
        vmCluster.setRequestConfig("{}");
        return vmCluster;
    }

    private ClusterComponent stubComponent(ComponentType type, Requirement requirement, ComponentProbe probe) {
        ClusterComponent component = mock(ClusterComponent.class);
        when(component.type()).thenReturn(type);
        when(component.requirementFor(any())).thenReturn(requirement);
        when(component.probe(any(), any())).thenReturn(probe);
        return component;
    }

    private ClusterComponentObserverImpl observer(List<ClusterComponent> components) {
        when(snapshotService.read(anyString())).thenReturn(VmClusterInternalRequestSnapshot.builder().build());
        when(provisioningService.stackOutputs(anyString(), anyBoolean(), any())).thenReturn(Map.of());
        when(repository.findByVmClusterIdAndComponentType(anyString(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ClusterComponentObserverImpl(components, repository, snapshotService, provisioningService);
    }

    @Test
    void observe_skipsNotApplicableComponents() {
        ClusterComponent skipped =
                stubComponent(ComponentType.GPU_OPERATOR, Requirement.NOT_APPLICABLE, ComponentProbe.ready());

        assertThat(observer(List.of(skipped)).observe(cluster())).isEmpty();
        verify(skipped, never()).probe(any(), any());
    }

    @Test
    void observe_probesApplicableComponentsAndPersists() {
        ClusterComponent probed = stubComponent(
                ComponentType.GPU_OPERATOR, Requirement.REQUIRED, ComponentProbe.notReady("no allocatable gpu"));

        var result = observer(List.of(probed)).observe(cluster());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).health()).isEqualTo(ComponentHealth.NOT_READY);
        assertThat(result.get(0).detail()).isEqualTo("no allocatable gpu");
        verify(repository).save(any());
    }

    @Test
    void observe_clearsLastErrorWhenReady() {
        ClusterComponent healthy =
                stubComponent(ComponentType.INGRESS, Requirement.REQUIRED, ComponentProbe.ready());
        VmClusterComponentEntity existing = new VmClusterComponentEntity();
        existing.setLastError("직전 실패 사유");
        when(repository.findByVmClusterIdAndComponentType(anyString(), any()))
                .thenReturn(Optional.of(existing));
        when(snapshotService.read(anyString())).thenReturn(VmClusterInternalRequestSnapshot.builder().build());
        when(provisioningService.stackOutputs(anyString(), anyBoolean(), any())).thenReturn(Map.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new ClusterComponentObserverImpl(List.of(healthy), repository, snapshotService, provisioningService)
                .observe(cluster());

        assertThat(existing.getLastError()).isNull();
        assertThat(existing.getHealth()).isEqualTo(ComponentHealth.READY);
    }

    @Test
    void observe_returnsEmptyWhenStackOutputsUnavailable() {
        // stack 이 사라진 클러스터에서 관측이 예외로 워크플로우를 죽이면 안 된다.
        ClusterComponent probed = stubComponent(ComponentType.AGENT, Requirement.REQUIRED, ComponentProbe.ready());
        ClusterComponentObserverImpl observer = observer(List.of(probed));
        when(provisioningService.stackOutputs(anyString(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("stack not found"));

        assertThat(observer.observe(cluster())).isEmpty();
    }

    @Test
    void observe_recordsUnknownWhenProbeThrows() {
        // 계약상 probe 는 예외를 던지지 않지만, 구현 실수가 나머지 컴포넌트 관측을 막으면 안 된다.
        ClusterComponent broken = mock(ClusterComponent.class);
        when(broken.type()).thenReturn(ComponentType.INGRESS);
        when(broken.requirementFor(any())).thenReturn(Requirement.REQUIRED);
        when(broken.probe(any(), any())).thenThrow(new RuntimeException("boom"));

        var result = observer(List.of(broken)).observe(cluster());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).health()).isEqualTo(ComponentHealth.UNKNOWN);
    }

    @Test
    void currentComponents_readsStoredStateWithoutProbing() {
        // 조회 API 가 매 요청마다 SSH 를 열면 안 된다.
        ClusterComponent probed = stubComponent(ComponentType.AGENT, Requirement.REQUIRED, ComponentProbe.ready());
        VmClusterComponentEntity row = new VmClusterComponentEntity();
        row.setComponentType(ComponentType.AGENT);
        row.setRequirement(Requirement.REQUIRED);
        row.setHealth(ComponentHealth.NOT_READY);
        row.setLastError("agent 미연결");
        when(repository.findByVmClusterId("vmc-001")).thenReturn(List.of(row));

        var result = observer(List.of(probed)).currentComponents("vmc-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ComponentType.AGENT);
        assertThat(result.get(0).detail()).isEqualTo("agent 미연결");
        verify(probed, never()).probe(any(), any());
    }
}
