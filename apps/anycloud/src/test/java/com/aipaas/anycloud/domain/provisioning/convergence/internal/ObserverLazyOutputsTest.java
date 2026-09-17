package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponent;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentProbe;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * 관측이 Pulumi 를 부르지 않아야 한다.
 *
 * <p>{@code stackOutputs} 는 Pulumi CLI 를 띄우고 원격 상태를 읽는 호출이다. 5분 주기 조정이
 * READY/DEGRADED 클러스터 전부에 대해 이걸 돌면 클러스터 수에 비례해 비용이 커진다. 지금 유일한
 * 컴포넌트인 agent probe 는 DB 만 읽어 outputs 가 필요 없다 — 필요한 쪽만 값을 당겨 쓰게 한다.
 */
class ObserverLazyOutputsTest extends AbstractUnitTest {

    @Mock
    VmClusterComponentRepository repository;

    @Mock
    VmClusterBootstrapSnapshotService snapshotService;

    @Mock
    ProvisioningService provisioningService;

    private VmClusterEntity cluster() {
        VmClusterEntity e = new VmClusterEntity();
        e.setId("id-1");
        e.setClusterName("demo");
        e.setStackName("stack-1");
        return e;
    }

    /** outputs 를 건드리지 않는 컴포넌트 — agent probe 와 같은 성격. */
    private ClusterComponent probeOnly(Supplier<Map<String, Object>> capture) {
        return new ClusterComponent() {
            @Override
            public ComponentType type() {
                return ComponentType.AGENT;
            }

            @Override
            public Requirement requirementFor(VmClusterInternalRequestSnapshot spec) {
                return Requirement.REQUIRED;
            }

            @Override
            public void apply(VmClusterEntity c, Supplier<Map<String, Object>> outputs) {
                outputs.get();
            }

            @Override
            public ComponentProbe probe(VmClusterEntity c, Supplier<Map<String, Object>> outputs) {
                return ComponentProbe.ready();
            }
        };
    }

    private ClusterComponentObserverImpl observer(ClusterComponent component) {
        when(snapshotService.read(any())).thenReturn(new VmClusterInternalRequestSnapshot());
        when(repository.findByVmClusterIdAndComponentType(anyString(), any())).thenReturn(Optional.empty());
        return new ClusterComponentObserverImpl(List.of(component), repository, snapshotService, provisioningService);
    }

    @Test
    void probeThatIgnoresOutputsNeverTriggersAPulumiCall() {
        ClusterComponentObserverImpl observer = observer(probeOnly(null));

        observer.observe(cluster());

        verify(provisioningService, never()).stackOutputs(anyString(), anyBoolean(), any());
    }

    @Test
    void observationStillReportsTheProbeResult() {
        // 지연 평가를 넣다가 관측 자체를 건너뛰면 수렴이 영영 판정되지 않는다.
        ClusterComponentObserverImpl observer = observer(probeOnly(null));

        assertThat(observer.observe(cluster())).hasSize(1);
    }
}
