package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapProgressReporter.BootstrapSubStep;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapStrategyResolver;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 진행률 역행 회귀 보호.
 *
 * <p>선언 순서만 검사하면 호출 순서가 어긋나도 통과한다 — ADDONS(64) 를 NODES_READY(60) 뒤에
 * 선언해 두고 앞에서 호출한 결함이 그렇게 살아남았다. 여기서는 bootstrap 을 실제로 돌려
 * reporter 가 받은 순서 그대로 percent 를 검증한다.
 */
class VmClusterBootstrapProgressOrderTest extends AbstractUnitTest {

    /** WorkflowSupportService.percentForStep(BOOTSTRAP) 과 같은 값. sub-step 은 이보다 커야 한다. */
    private static final int BOOTSTRAP_ENTRY_PERCENT = 33;

    private final List<BootstrapSubStep> recorded = new ArrayList<>();

    private final VmClusterBootstrapProgressReporter recordingReporter = (cluster, subStep) -> recorded.add(subStep);

    private VmClusterBootstrapServiceImpl service() {
        VmClusterBootstrapStrategyResolver resolver = Mockito.mock(VmClusterBootstrapStrategyResolver.class);
        Mockito.when(resolver.resolve(Mockito.any())).thenReturn(Mockito.mock(VmClusterBootstrapStrategy.class));
        return new VmClusterBootstrapServiceImpl(
                Mockito.mock(VmClusterRemoteAccessService.class),
                resolver,
                Mockito.mock(VmClusterBootstrapSnapshotService.class),
                Mockito.mock(VmClusterNodeResolver.class),
                recordingReporter);
    }

    @Test
    void bootstrap_reportsSubStepsInAscendingPercentOrder() {
        service().bootstrap(new VmClusterEntity(), Map.of());

        assertThat(recorded).isNotEmpty();
        int last = BOOTSTRAP_ENTRY_PERCENT;
        for (BootstrapSubStep step : recorded) {
            assertThat(step.percent())
                    .as("%s 의 percent 가 직전 값 %d 보다 커야 진행 바가 역행하지 않는다", step, last)
                    .isGreaterThan(last);
            last = step.percent();
        }
    }

    @Test
    void bootstrap_reportsEverySubStepExceptHaOnlyOnes() {
        // extraMasterHosts 가 비면 EXTRA_MASTER_JOIN 은 호출되지 않는다 (single-master).
        service().bootstrap(new VmClusterEntity(), Map.of());

        assertThat(recorded).doesNotContain(BootstrapSubStep.EXTRA_MASTER_JOIN);
        assertThat(recorded)
                .containsExactly(
                        BootstrapSubStep.NODE_PREPARATION,
                        BootstrapSubStep.MASTER_INIT,
                        BootstrapSubStep.WORKER_JOIN,
                        BootstrapSubStep.ADDONS,
                        BootstrapSubStep.NODES_READY);
    }

    @Test
    void declarationOrderMatchesPercentOrder() {
        // 선언 순서가 호출 순서와 같다는 계약 (javadoc) 을 강제.
        int last = BOOTSTRAP_ENTRY_PERCENT;
        for (BootstrapSubStep step : BootstrapSubStep.values()) {
            assertThat(step.percent()).as("%s", step).isGreaterThan(last);
            last = step.percent();
        }
        assertThat(last).as("마지막 sub-step 은 VERIFY(90) 미만이어야 한다").isLessThan(90);
    }
}
