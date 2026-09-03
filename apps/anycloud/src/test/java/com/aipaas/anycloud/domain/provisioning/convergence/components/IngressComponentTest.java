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

class IngressComponentTest {

    private final VmClusterRemoteAccessService remoteAccess = mock(VmClusterRemoteAccessService.class);
    private final IngressComponent component = new IngressComponent(remoteAccess);

    @Test
    void type_isIngress() {
        assertThat(component.type()).isEqualTo(ComponentType.INGRESS);
    }

    @Test
    void required_whenIngressRequested() {
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().enableIngress(true).build();
        assertThat(component.requirementFor(spec)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void notApplicable_whenFlagIsNull() {
        assertThat(component.requirementFor(VmClusterInternalRequestSnapshot.builder().build()))
                .isEqualTo(Requirement.NOT_APPLICABLE);
    }

    @Test
    void isDeploymentAvailable_trueWhenAtLeastOneReadyReplica() {
        assertThat(IngressComponent.isDeploymentAvailable("1")).isTrue();
        assertThat(IngressComponent.isDeploymentAvailable("3")).isTrue();
    }

    @Test
    void isDeploymentAvailable_falseWhenZeroOrEmpty() {
        assertThat(IngressComponent.isDeploymentAvailable("0")).isFalse();
        assertThat(IngressComponent.isDeploymentAvailable("")).isFalse();
        assertThat(IngressComponent.isDeploymentAvailable(null)).isFalse();
    }

    @Test
    void probe_readyWhenControllerHasReadyReplica() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenReturn("1");
        assertThat(component.probe(new VmClusterEntity(), Map.of()).health())
                .isEqualTo(ComponentHealth.READY);
    }

    @Test
    void probe_notReadyWhenNoReadyReplica() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenReturn("0");
        assertThat(component.probe(new VmClusterEntity(), Map.of()).health())
                .isEqualTo(ComponentHealth.NOT_READY);
    }

    @Test
    void probe_unknownWhenTransportFails() {
        when(remoteAccess.runOnMaster(any(), any(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("ssh timeout"));
        assertThat(component.probe(new VmClusterEntity(), Map.of()).health())
                .isEqualTo(ComponentHealth.UNKNOWN);
    }
}
