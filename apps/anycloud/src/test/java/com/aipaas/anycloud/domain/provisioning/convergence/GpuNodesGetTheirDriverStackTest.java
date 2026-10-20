package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * GPU 인스턴스로 생성해도 operator 부재 시 GPU 사용 불가.
 *
 * <p>화면 전송 플래그에만 의존하던 구간에 해당 플래그가 수신되지 않아, GPU 노드 생성 후에도
 * 드라이버 스택 미설치.
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
        // 미사용 드라이버 스택 설치는 노드 자원만 소모.
        assertThat(RequestedAddons.catalogEntries(spec("AWS", "t3.large", null)))
                .doesNotContainKey("nvidia-gpu-operator");
    }

    @Test
    void anExplicitOffIsRespected() {
        // 명시적 비활성화는 유지. 자동 판정이 사용자 결정을 덮으면 안 됨.
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
        // 이름 규칙은 CSP 별 상이. OpenStack flavor 이름은 운영자가 지정.
        assertThat(RequestedAddons.catalogEntries(spec("OpenStack", "32-256-gpu", null)))
                .containsKey("nvidia-gpu-operator");
    }
}
