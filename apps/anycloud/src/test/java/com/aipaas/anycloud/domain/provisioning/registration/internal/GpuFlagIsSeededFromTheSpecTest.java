package com.aipaas.anycloud.domain.provisioning.registration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * hasGpuNodes 플래그는 화면이 보내야 채워진다.
 *
 * <p>API 를 직접 부르면 비어 있고, agent 의 backfill 은 드라이버가 올라온 뒤라 그때까지 GPU
 * 클러스터가 일반 클러스터로 보인다. 고른 인스턴스 타입으로 판정하면 등록 시점에 정해진다.
 */
class GpuFlagIsSeededFromTheSpecTest {

    private final VmClusterRegistrationServiceImpl service = new VmClusterRegistrationServiceImpl(null, null);

    private boolean seedFrom(String json) {
        return service.extractHasGpuNodes(json);
    }

    @Test
    void aGpuWorkerIsEnoughWithoutTheFlag() {
        assertThat(
                        seedFrom(
                                """
                        {"clusterProvider":"GCP","masterVmSpec":"e2-standard-2",
                         "workerVmSpec":"g2-standard-4"}"""))
                .isTrue();
    }

    @Test
    void anExplicitFlagStillWins() {
        assertThat(
                        seedFrom(
                                """
                        {"hasGpuNodes":true,"clusterProvider":"AWS","masterVmSpec":"t3.large",
                         "workerVmSpec":"t3.large"}"""))
                .isTrue();
    }

    @Test
    void aPlainClusterStaysFalse() {
        assertThat(
                        seedFrom(
                                """
                        {"clusterProvider":"AWS","masterVmSpec":"t3.large",
                         "workerVmSpec":"t3.large"}"""))
                .isFalse();
    }
}
