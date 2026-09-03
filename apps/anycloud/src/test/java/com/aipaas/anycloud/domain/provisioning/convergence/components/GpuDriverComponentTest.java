package com.aipaas.anycloud.domain.provisioning.convergence.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver.VmClusterNode;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GpuDriverComponentTest {

    private final VmClusterRemoteAccessService remoteAccess = mock(VmClusterRemoteAccessService.class);
    private final VmClusterNodeResolver nodeResolver = mock(VmClusterNodeResolver.class);
    private final GpuDriverComponent component = new GpuDriverComponent(remoteAccess, nodeResolver);

    @Test
    void type_isGpuDriver() {
        assertThat(component.type()).isEqualTo(ComponentType.GPU_DRIVER);
    }

    @Test
    void required_whenGpuOperatorRequested() {
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().enableGpuOperator(true).build();
        assertThat(component.requirementFor(spec)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void hasGpuDevice_trueWhenNvidiaSmiListsDevice() {
        assertThat(GpuDriverComponent.hasGpuDevice("GPU 0: NVIDIA A10G (UUID: GPU-abc)"))
                .isTrue();
    }

    @Test
    void hasGpuDevice_falseWhenNoDevicesFound() {
        assertThat(GpuDriverComponent.hasGpuDevice("No devices were found")).isFalse();
        assertThat(GpuDriverComponent.hasGpuDevice("")).isFalse();
        assertThat(GpuDriverComponent.hasGpuDevice(null)).isFalse();
    }

    @Test
    void probe_readyWhenAnyWorkerReportsDevice() {
        when(nodeResolver.readNodes(any()))
                .thenReturn(List.of(new VmClusterNode("master", "10.0.0.1"), new VmClusterNode("worker", "10.0.0.2")));
        when(remoteAccess.runOnHost(any(), any(), eq("10.0.0.2"), anyString(), any(Duration.class)))
                .thenReturn("GPU 0: NVIDIA A10G (UUID: GPU-abc)");
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.READY);
    }

    @Test
    void probe_notReadyWhenNoWorkerReportsDevice() {
        when(nodeResolver.readNodes(any())).thenReturn(List.of(new VmClusterNode("worker", "10.0.0.2")));
        when(remoteAccess.runOnHost(any(), any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("No devices were found");
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.NOT_READY);
    }

    @Test
    void probe_unknownWhenEveryWorkerProbeFails() {
        // 전부 transport 실패면 드라이버 부재를 단정할 수 없다.
        when(nodeResolver.readNodes(any())).thenReturn(List.of(new VmClusterNode("worker", "10.0.0.2")));
        when(remoteAccess.runOnHost(any(), any(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("ssh timeout"));
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.UNKNOWN);
    }

    @Test
    void probe_unknownWhenNoWorkerNodeResolved() {
        when(nodeResolver.readNodes(any())).thenReturn(List.of(new VmClusterNode("master", "10.0.0.1")));
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.UNKNOWN);
        assertThat(result.detail()).contains("worker");
    }
}
