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
 * 관측 경로의 Pulumi 호출 차단.
 *
 * <p>{@code stackOutputs} 는 Pulumi CLI 실행과 원격 상태 조회를 수반. 5분 주기 조정이
 * READY, DEGRADED 클러스터 전체에 대해 실행하면 클러스터 수에 비례해 비용 증가. 현재 유일
 * 컴포넌트인 agent probe 는 DB 만 조회하므로 outputs 불필요. 필요한 구현만 값을 조회.
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

    /** outputs 를 건드리지 않는 컴포넌트: agent probe 와 같은 성격. */
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
        // 지연 평가 적용 과정에서 관측을 생략하면 수렴 판정 불가.
        ClusterComponentObserverImpl observer = observer(probeOnly(null));

        assertThat(observer.observe(cluster())).hasSize(1);
    }
}
