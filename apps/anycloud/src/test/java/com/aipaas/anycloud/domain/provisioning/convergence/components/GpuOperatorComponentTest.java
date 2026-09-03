package com.aipaas.anycloud.domain.provisioning.convergence.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GpuOperatorComponentTest {

    private final VmClusterRemoteAccessService remoteAccess = mock(VmClusterRemoteAccessService.class);
    private final GpuOperatorComponent component = new GpuOperatorComponent(remoteAccess);

    @Test
    void type_isGpuOperator() {
        assertThat(component.type()).isEqualTo(ComponentType.GPU_OPERATOR);
    }

    @Test
    void required_whenGpuOperatorRequested() {
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().enableGpuOperator(true).build();
        assertThat(component.requirementFor(spec)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void notApplicable_whenGpuNotRequested() {
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().enableGpuOperator(false).build();
        assertThat(component.requirementFor(spec)).isEqualTo(Requirement.NOT_APPLICABLE);
    }

    @Test
    void notApplicable_whenFlagIsNull() {
        // 과거 요청에는 필드가 없어 null 로 역직렬화된다. NPE 로 조정 루프를 죽이면 안 된다.
        VmClusterInternalRequestSnapshot spec = VmClusterInternalRequestSnapshot.builder().build();
        assertThat(component.requirementFor(spec)).isEqualTo(Requirement.NOT_APPLICABLE);
    }

    @Test
    void totalAllocatableGpu_sumsSpaceSeparatedCounts() {
        assertThat(GpuOperatorComponent.totalAllocatableGpu("1 2 4")).isEqualTo(7);
    }

    @Test
    void totalAllocatableGpu_ignoresBlankAndNonNumeric() {
        // GPU 없는 노드는 해당 키가 없어 빈 토큰으로 나온다.
        assertThat(GpuOperatorComponent.totalAllocatableGpu("  1   <none> 2 ")).isEqualTo(3);
    }

    @Test
    void totalAllocatableGpu_zeroForNullOrBlank() {
        assertThat(GpuOperatorComponent.totalAllocatableGpu(null)).isZero();
        assertThat(GpuOperatorComponent.totalAllocatableGpu("   ")).isZero();
    }

    @Test
    void probe_readyWhenAnyNodeReportsGpu() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenReturn("0 1");
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.READY);
    }

    @Test
    void probe_notReadyWhenNoNodeReportsGpu() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenReturn("0 0");
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.NOT_READY);
        assertThat(result.detail()).contains("nvidia.com/gpu");
    }

    @Test
    void probe_unknownWhenTransportFails() {
        // SSH 불통은 "GPU 가 없다" 와 다른 사건이다. NOT_READY 로 뭉치면 네트워크가
        // 흔들릴 때마다 클러스터 상태가 오간다.
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("ssh connect timeout"));
        var result = component.probe(new VmClusterEntity(), Map.of());
        assertThat(result.health()).isEqualTo(ComponentHealth.UNKNOWN);
        assertThat(result.detail()).contains("ssh connect timeout");
    }

    @Test
    void apply_installsChartWithoutBlockingWait() {
        org.mockito.ArgumentCaptor<String> captured = org.mockito.ArgumentCaptor.forClass(String.class);
        when(remoteAccess.runOnMaster(any(), any(), captured.capture(), any(Duration.class)))
                .thenReturn("");

        component.apply(new VmClusterEntity(), Map.of());

        String script = captured.getValue();
        assertThat(script).contains("helm upgrade --install gpu-operator nvidia/gpu-operator");
        assertThat(script).contains("kubectl create namespace gpu-operator");
        // operator 가 드라이버를 관리한다. 호스트 설치와 병행하면 driver 파드가 종료된다.
        assertThat(script).contains("driver.enabled=true");
        assertThat(script).doesNotContain("ubuntu-drivers");
        // kubectl wait 는 consumer 스레드를 15분 묶는다. 준비 확인은 probe 가 한다.
        assertThat(script).doesNotContain("kubectl wait");
        // 실패를 삼키면 apply 가 성공을 잘못 보고한다.
        assertThat(script).doesNotContain("|| true");
    }

    @Test
    void apply_propagatesFailure() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("helm exit 1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> component.apply(new VmClusterEntity(), Map.of()))
                .hasMessageContaining("helm exit 1");
    }
}
