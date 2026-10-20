package com.aipaas.anycloud.domain.provisioning.registration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * hasGpuNodes 플래그는 화면 전송 시에만 설정.
 *
 * <p>API 직접 호출 시 빈 값이고 agent 의 backfill 은 드라이버 기동 이후라 그 시점까지 GPU
 * 클러스터가 일반 클러스터로 표시. 선택한 인스턴스 타입 기준 판정 시 등록 시점에 확정.
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
