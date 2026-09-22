package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * GPU 인스턴스로 만들어도 operator 가 없으면 GPU 를 쓸 수 없다.
 *
 * <p>화면이 보내는 플래그에만 기대던 동안 그 플래그가 한 번도 오지 않아, GPU 노드를 만들어도
 * 드라이버 스택이 올라가지 않았다.
 */
class GpuNodesGetTheirDriverStackTest extends AbstractUnitTest {

    private static VmClusterInternalRequestSnapshot spec(String provider, String worker, Boolean gpuOperator) {
        return VmClusterInternalRequestSnapshot.builder()
                .clusterProvider(provider)
                .masterVmSpec("t3.large")
                .workerVmSpec(worker)
                .enableGpuOperator(gpuOperator)
                .build();
    }

    @Test
    void aGpuWorkerBringsTheOperatorWithoutBeingAsked() {
        assertThat(RequestedAddons.catalogEntries(spec("AWS", "g6.24xlarge", null)))
                .containsKey("nvidia-gpu-operator");
    }

    @Test
    void anOrdinaryClusterDoesNotGetIt() {
        // 쓰지도 않을 드라이버 스택을 올리면 노드 자원만 먹는다.
        assertThat(RequestedAddons.catalogEntries(spec("AWS", "t3.large", null)))
                .doesNotContainKey("nvidia-gpu-operator");
    }

    @Test
    void anExplicitOffIsRespected() {
        // 직접 끈 것은 존중한다. 자동 판정이 사용자의 결정을 되돌리면 안 된다.
        assertThat(RequestedAddons.catalogEntries(spec("AWS", "g6.24xlarge", false)))
                .doesNotContainKey("nvidia-gpu-operator");
    }

    @Test
    void anExplicitOnWorksEvenWithoutAGpuSpec() {
        assertThat(RequestedAddons.catalogEntries(spec("AWS", "t3.large", true)))
                .containsKey("nvidia-gpu-operator");
    }

    @Test
    void openstackGpuFlavorsCountToo() {
        // 이름 규칙이 CSP 마다 다르다. OpenStack flavor 는 운영자가 이름을 정한다.
        assertThat(RequestedAddons.catalogEntries(spec("OpenStack", "32-256-gpu", null)))
                .containsKey("nvidia-gpu-operator");
    }
}
