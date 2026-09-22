package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** GPU 워커를 골랐는데 operator 가 빠지면 드라이버 없이 READY 가 된다. */
class RequestedAddonsGpuTest {

    @ParameterizedTest
    @CsvSource({
        "AWS, t3.large, g5.2xlarge",
        "GCP, e2-standard-2, a2-highgpu-1g",
        "IBM, bx2-2x8, gx2-8x64x1v100",
        "Alibaba, ecs.g9i.large, ecs.gn7i-c8g1.2xlarge"
    })
    void gpuWorkerPullsInTheOperator(String provider, String master, String worker) {
        VmClusterInternalRequestSnapshot spec = new VmClusterInternalRequestSnapshot();
        spec.setClusterProvider(provider);
        spec.setMasterVmSpec(master);
        spec.setWorkerVmSpec(worker);

        assertThat(RequestedAddons.catalogEntries(spec)).containsKey("nvidia-gpu-operator");
    }
}
