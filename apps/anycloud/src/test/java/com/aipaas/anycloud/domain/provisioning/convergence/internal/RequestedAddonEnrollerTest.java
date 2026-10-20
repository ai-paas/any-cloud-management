package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.addon.AddonService;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestedAddonEnrollerTest {

    private final AddonService addonService = mock(AddonService.class);
    private final VmClusterBootstrapSnapshotService snapshotService = mock(VmClusterBootstrapSnapshotService.class);
    private final RequestedAddonEnroller enroller = new RequestedAddonEnroller(addonService, snapshotService);

    private VmClusterEntity cluster() {
        VmClusterEntity vmCluster = new VmClusterEntity();
        vmCluster.setClusterId("cluster-001");
        vmCluster.setClusterName("demo-aws-01");
        vmCluster.setRequestConfig("{}");
        return vmCluster;
    }

    private void request(Boolean gpu, Boolean ingress) {
        when(snapshotService.read(anyString()))
                .thenReturn(VmClusterInternalRequestSnapshot.builder()
                        .enableGpuOperator(gpu)
                        .enableIngress(ingress)
                        .build());
    }

    @Test
    void enrollsIngressWithCatalogType() {
        request(false, true);
        ArgumentCaptor<AddonSpec> spec = ArgumentCaptor.forClass(AddonSpec.class);

        enroller.enroll(cluster());

        verify(addonService).create(anyString(), spec.capture());
        assertThat(spec.getValue().catalogId()).isEqualTo("ingress-nginx");
        assertThat(spec.getValue().type()).isEqualTo(AddonType.INGRESS_NGINX);
        // 나머지는 카탈로그 기본값 사용. 버전을 두 곳에 기재하면 불일치.
        assertThat(spec.getValue().chartVersion()).isNull();
        assertThat(spec.getValue().repoUrl()).isNull();
    }

    @Test
    void enrollsBothWhenBothRequested() {
        request(true, true);
        ArgumentCaptor<AddonSpec> spec = ArgumentCaptor.forClass(AddonSpec.class);

        enroller.enroll(cluster());

        verify(addonService, org.mockito.Mockito.times(2)).create(anyString(), spec.capture());
        assertThat(spec.getAllValues())
                .extracting(AddonSpec::catalogId)
                .containsExactly("nvidia-gpu-operator", "ingress-nginx");
    }

    @Test
    void enrollsNothingWhenNothingRequested() {
        request(false, false);
        enroller.enroll(cluster());
        verify(addonService, never()).create(anyString(), any());
    }

    @Test
    void skipsWhenClusterNotRegisteredYet() {
        request(true, true);
        VmClusterEntity vmCluster = cluster();
        vmCluster.setClusterId(null);

        enroller.enroll(vmCluster);

        verify(addonService, never()).create(anyString(), any());
    }

    @Test
    void oneFailureDoesNotBlockTheOtherOrThrow() {
        // addon 등록 실패가 BOOTSTRAP 실패로 이어지면 안 됨. 이후 처리는 조정 루프 담당.
        request(true, true);
        when(addonService.create(anyString(), any()))
                .thenThrow(new IllegalStateException("duplicate"))
                .thenReturn(null);

        assertThatCode(() -> enroller.enroll(cluster())).doesNotThrowAnyException();
        verify(addonService, org.mockito.Mockito.times(2)).create(anyString(), any());
    }

    @Test
    void usesClusterIdNotVmClusterId() {
        // addon 은 cluster.id 기준 연결. vm_cluster.id 전달 시 매칭 불가.
        request(false, true);
        ArgumentCaptor<String> clusterId = ArgumentCaptor.forClass(String.class);

        enroller.enroll(cluster());

        verify(addonService).create(clusterId.capture(), any());
        assertThat(clusterId.getValue()).isEqualTo("cluster-001");
    }

    @Test
    void snapshotFailureDoesNotThrow() {
        when(snapshotService.read(anyString())).thenThrow(new IllegalStateException("bad json"));
        assertThatCode(() -> enroller.enroll(cluster())).doesNotThrowAnyException();
    }
}
