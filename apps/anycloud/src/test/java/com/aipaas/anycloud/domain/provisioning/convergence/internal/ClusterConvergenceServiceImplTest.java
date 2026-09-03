package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.convergence.ClusterComponentObserver;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentObservation;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentType;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterConvergenceServiceImplTest {

    private final ClusterComponentObserver observer = mock(ClusterComponentObserver.class);
    private final RequestedAddonInspector addonInspector = mock(RequestedAddonInspector.class);

    /** 테스트에서 대기 0 — 실제 지연은 설정값이라 로직만 검증한다. */
    private ClusterConvergenceServiceImpl service(int maxAttempts) {
        when(addonInspector.inspect(any())).thenReturn(List.of());
        return new ClusterConvergenceServiceImpl(observer, addonInspector, maxAttempts, Duration.ZERO);
    }

    private ComponentObservation required(ComponentHealth health) {
        return new ComponentObservation(ComponentType.AGENT, Requirement.REQUIRED, health, null);
    }

    @Test
    void returnsTrueOnFirstAttemptWhenSatisfied() {
        when(observer.observe(any())).thenReturn(List.of(required(ComponentHealth.READY)));

        assertThat(service(3).convergeWithinBudget(new VmClusterEntity())).isTrue();
        verify(observer, times(1)).observe(any());
    }

    @Test
    void retriesUntilSatisfied() {
        when(observer.observe(any()))
                .thenReturn(List.of(required(ComponentHealth.NOT_READY)))
                .thenReturn(List.of(required(ComponentHealth.READY)));

        assertThat(service(3).convergeWithinBudget(new VmClusterEntity())).isTrue();
        verify(observer, times(2)).observe(any());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        // consumer 스레드를 오래 붙잡지 않는 것이 이 제한의 목적이다.
        when(observer.observe(any())).thenReturn(List.of(required(ComponentHealth.NOT_READY)));

        assertThat(service(3).convergeWithinBudget(new VmClusterEntity())).isFalse();
        verify(observer, times(3)).observe(any());
    }

    @Test
    void inconclusiveIsNotConverged() {
        // 확인하지 못한 것을 충족으로 보고하면 관측을 넣은 의미가 없다.
        when(observer.observe(any())).thenReturn(List.of(required(ComponentHealth.UNKNOWN)));

        assertThat(service(2).convergeWithinBudget(new VmClusterEntity())).isFalse();
    }

    @Test
    void noApplicableComponentsConvergesImmediately() {
        // GPU 도 ingress 도 요청하지 않은 평범한 클러스터는 즉시 통과해야 한다.
        when(observer.observe(any())).thenReturn(List.of());

        assertThat(service(3).convergeWithinBudget(new VmClusterEntity())).isTrue();
        verify(observer, times(1)).observe(any());
    }

    @Test
    void observerFailureDoesNotThrow() {
        when(observer.observe(any())).thenThrow(new RuntimeException("boom"));

        assertThat(service(2).convergeWithinBudget(new VmClusterEntity())).isFalse();
    }
}
