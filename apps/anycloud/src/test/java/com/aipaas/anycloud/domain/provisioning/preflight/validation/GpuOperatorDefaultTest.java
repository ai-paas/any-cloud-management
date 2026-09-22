package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GPU 워커를 골랐는데 operator 가 꺼지면 드라이버 없이 READY 가 된다.
 *
 * <p>IBM 과 OpenStack 이 실제로 그랬다 — 다른 CSP 만 켜져 같은 요청이 CSP 마다 다르게 끝났다.
 */
class GpuOperatorDefaultTest {

    private static final String KEY = "anycloud-k8s:enableGpuOperator";
    private static final String MASTER = "anycloud-k8s:masterInstanceType";
    private static final String WORKER = "anycloud-k8s:workerInstanceType";

    @ParameterizedTest
    @CsvSource({
        "AWS, t3.large, g5.2xlarge",
        "GCP, e2-standard-2, a2-highgpu-1g",
        "IBM, bx2-2x8, gx2-8x64x1v100",
        "ALIBABA, ecs.g9i.large, ecs.gn7i-c8g1.2xlarge",
        "OPENSTACK, m1.large, 4-8-gpu"
    })
    void gpuWorkerTurnsTheOperatorOn(String provider, String master, String worker) {
        Map<String, String> config = new HashMap<>(Map.of(MASTER, master, WORKER, worker));

        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.valueOf(provider), config);

        assertThat(config.get(KEY)).isEqualTo("true");
    }

    @ParameterizedTest
    @CsvSource({"AWS, t3.large, t3.large", "IBM, bx2-2x8, bx2-2x8"})
    void plainWorkerLeavesItOff(String provider, String master, String worker) {
        Map<String, String> config = new HashMap<>(Map.of(MASTER, master, WORKER, worker));

        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.valueOf(provider), config);

        assertThat(config.get(KEY)).isEqualTo("false");
    }

    @org.junit.jupiter.api.Test
    void anExplicitChoiceIsKept() {
        Map<String, String> config = new HashMap<>(Map.of(MASTER, "t3.large", WORKER, "g5.2xlarge", KEY, "false"));

        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.AWS, config);

        assertThat(config.get(KEY)).isEqualTo("false");
    }
}
