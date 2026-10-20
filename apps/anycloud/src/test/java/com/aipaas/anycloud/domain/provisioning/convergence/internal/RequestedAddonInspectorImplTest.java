package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.ClusterAddonRepository;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestedAddonInspectorImplTest {

    private final ClusterAddonRepository addonRepository = mock(ClusterAddonRepository.class);
    private final VmClusterBootstrapSnapshotService snapshotService = mock(VmClusterBootstrapSnapshotService.class);
    private final RequestedAddonInspectorImpl inspector =
            new RequestedAddonInspectorImpl(addonRepository, snapshotService);

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

    private ClusterAddonEntity addon(String catalogId, AddonState state) {
        ClusterAddonEntity entity = new ClusterAddonEntity();
        entity.setCatalogId(catalogId);
        entity.setState(state);
        return entity;
    }

    @Test
    void requestingNothing_yieldsNoSignals() {
        request(false, false);
        assertThat(inspector.inspect(cluster())).isEmpty();
    }

    @Test
    void requestedAddonSucceeded_isReady() {
        request(true, false);
        when(addonRepository.findByClusterId("cluster-001"))
                .thenReturn(List.of(addon("nvidia-gpu-operator", AddonState.SUCCEEDED)));

        var signals = inspector.inspect(cluster());

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).source()).isEqualTo("nvidia-gpu-operator");
        assertThat(signals.get(0).requirement()).isEqualTo(Requirement.REQUIRED);
        assertThat(signals.get(0).health()).isEqualTo(ComponentHealth.READY);
    }

    @Test
    void requestedAddonFailed_isNotReady() {
        request(true, false);
        ClusterAddonEntity failed = addon("nvidia-gpu-operator", AddonState.FAILED);
        failed.setLastError("helm install timed out");
        when(addonRepository.findByClusterId("cluster-001")).thenReturn(List.of(failed));

        var signals = inspector.inspect(cluster());

        assertThat(signals.get(0).health()).isEqualTo(ComponentHealth.NOT_READY);
        assertThat(signals.get(0).detail()).isEqualTo("helm install timed out");
    }

    @Test
    void installInProgress_isUnknownNotFailure() {
        // 설치 진행 중 항목의 실패 판정 금지. 다음 주기 대기.
        request(true, false);
        for (AddonState inFlight : List.of(AddonState.PENDING, AddonState.ENQUEUED, AddonState.INSTALLING)) {
            when(addonRepository.findByClusterId("cluster-001"))
                    .thenReturn(List.of(addon("nvidia-gpu-operator", inFlight)));
            assertThat(inspector.inspect(cluster()).get(0).health())
                    .as("state=%s", inFlight)
                    .isEqualTo(ComponentHealth.UNKNOWN);
        }
    }

    @Test
    void requestedButNeverRegistered_isUnknown() {
        // agent 미연결로 auto-enroll 미실행 상태. 미설치로 단정 불가.
        request(true, true);
        when(addonRepository.findByClusterId("cluster-001")).thenReturn(List.of());

        var signals = inspector.inspect(cluster());

        assertThat(signals).hasSize(2);
        assertThat(signals).allMatch(s -> s.health() == ComponentHealth.UNKNOWN);
    }

    @Test
    void operatorAddedAddon_isNotIncluded() {
        // 클러스터 생성 요청에 포함되지 않으므로 미설치가 DEGRADED 사유가 되면 안 됨.
        request(false, false);
        when(addonRepository.findByClusterId("cluster-001")).thenReturn(List.of(addon("velero", AddonState.FAILED)));

        assertThat(inspector.inspect(cluster())).isEmpty();
    }

    @Test
    void unregisteredClusterYieldsNothing() {
        // cluster row 부재 시 addon 도 부재.
        VmClusterEntity vmCluster = cluster();
        vmCluster.setClusterId(null);
        request(true, true);

        assertThat(inspector.inspect(vmCluster)).isEmpty();
    }

    @Test
    void repositoryFailureYieldsNothing() {
        request(true, false);
        when(addonRepository.findByClusterId("cluster-001")).thenThrow(new IllegalStateException("db down"));

        assertThat(inspector.inspect(cluster())).isEmpty();
    }
}
