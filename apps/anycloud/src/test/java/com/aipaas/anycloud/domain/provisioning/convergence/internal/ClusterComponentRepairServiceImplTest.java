package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClusterComponentRepairServiceImplTest {

    private final VmClusterComponentRepository repository = mock(VmClusterComponentRepository.class);
    private final ProvisioningService provisioningService = mock(ProvisioningService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T10:00:00Z"), ZoneOffset.UTC);

    private VmClusterEntity cluster() {
        VmClusterEntity vmCluster = new VmClusterEntity();
        vmCluster.setId("vmc-001");
        vmCluster.setClusterName("demo-aws-01");
        vmCluster.setStackName("demo-aws-01-dev");
        return vmCluster;
    }

    private ClusterComponent agentComponent() {
        ClusterComponent component = mock(ClusterComponent.class);
        when(component.type()).thenReturn(ComponentType.AGENT);
        return component;
    }

    private VmClusterComponentEntity row() {
        VmClusterComponentEntity entity = new VmClusterComponentEntity();
        entity.setVmClusterId("vmc-001");
        entity.setComponentType(ComponentType.AGENT);
        entity.setRequirement(Requirement.REQUIRED);
        entity.setHealth(ComponentHealth.NOT_READY);
        entity.setAttempts(7);
        entity.setNextAttemptAt(ZonedDateTime.now(clock).plusHours(1));
        entity.setLastError("직전 실패");
        return entity;
    }

    private ClusterComponentRepairServiceImpl service(ClusterComponent component) {
        when(provisioningService.stackOutputs(anyString(), anyBoolean(), any())).thenReturn(Map.of());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        return new ClusterComponentRepairServiceImpl(List.of(component), repository, provisioningService, clock);
    }

    @Test
    void appliesAndResetsBackoff() {
        // 운영자가 원인을 고쳤다는 전제다. 이전 백오프를 끌고 가면 즉시 확인이 불가능하다.
        ClusterComponent component = agentComponent();
        VmClusterComponentEntity entity = row();
        when(repository.findByVmClusterIdAndComponentType("vmc-001", ComponentType.AGENT))
                .thenReturn(Optional.of(entity));

        service(component).repair(cluster(), ComponentType.AGENT);

        verify(component).apply(any(), any());
        assertThat(entity.getAttempts()).isZero();
        assertThat(entity.getNextAttemptAt()).isNull();
        assertThat(entity.getLastError()).isNull();
        assertThat(entity.getLastAppliedAt()).isEqualTo(ZonedDateTime.now(clock));
    }

    @Test
    void doesNotMarkHealthy() {
        // 적용 성공이 준비 완료는 아니다. 판정은 다음 probe 몫이다.
        ClusterComponent component = agentComponent();
        VmClusterComponentEntity entity = row();
        when(repository.findByVmClusterIdAndComponentType("vmc-001", ComponentType.AGENT))
                .thenReturn(Optional.of(entity));

        service(component).repair(cluster(), ComponentType.AGENT);

        assertThat(entity.getHealth()).isEqualTo(ComponentHealth.NOT_READY);
    }

    @Test
    void applyFailurePropagatesAndLeavesRowUntouched() {
        // 운영자 요청은 실패를 숨기면 안 된다. 400/500 으로 드러나야 한다.
        ClusterComponent component = agentComponent();
        org.mockito.Mockito.doThrow(new IllegalStateException("ssh refused"))
                .when(component)
                .apply(any(), any());

        assertThatThrownBy(() -> service(component).repair(cluster(), ComponentType.AGENT))
                .hasMessageContaining("ssh refused");
        verify(repository, never()).save(any());
    }

    @Test
    void unknownComponentIsRejected() {
        ClusterComponentRepairServiceImpl service =
                new ClusterComponentRepairServiceImpl(List.of(), repository, provisioningService, clock);

        assertThatThrownBy(() -> service.repair(cluster(), ComponentType.AGENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
