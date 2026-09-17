package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * READY 도달 시각.
 *
 * <p>VERIFY 안에서 수렴하면 markReady 가 readyAt 을 채우지만, 예산을 다 쓰고 DEGRADED 로
 * 떨어진 뒤 조정 루프가 READY 로 올리는 경로는 채우지 않았다. 화면의 단계별 소요 시간이 그
 * 값에 걸려 있어 "완료" 구간이 항상 비어 보인다.
 */
class ReadyAtOnReconcileTest extends AbstractUnitTest {

    private VmClusterEntity degraded() {
        VmClusterEntity e = new VmClusterEntity();
        e.setClusterName("demo");
        e.setProvisioningStatus(VmClusterStatus.DEGRADED);
        return e;
    }

    @Test
    void reachingReadyStampsReadyAt() {
        VmClusterEntity e = degraded();

        ClusterConvergenceOrchestratorImpl.stampReadyAt(e, VmClusterStatus.READY);

        assertThat(e.getReadyAt()).isNotNull();
    }

    @Test
    void doesNotStampForOtherStates() {
        VmClusterEntity e = degraded();

        ClusterConvergenceOrchestratorImpl.stampReadyAt(e, VmClusterStatus.DEGRADED);

        assertThat(e.getReadyAt()).isNull();
    }

    @Test
    void keepsTheFirstReadyAt() {
        // READY -> DEGRADED -> READY 를 오갈 때마다 갱신하면 최초 도달 시각을 잃는다.
        VmClusterEntity e = degraded();
        ClusterConvergenceOrchestratorImpl.stampReadyAt(e, VmClusterStatus.READY);
        java.time.LocalDateTime first = e.getReadyAt();

        ClusterConvergenceOrchestratorImpl.stampReadyAt(e, VmClusterStatus.READY);

        assertThat(e.getReadyAt()).isEqualTo(first);
    }
}
