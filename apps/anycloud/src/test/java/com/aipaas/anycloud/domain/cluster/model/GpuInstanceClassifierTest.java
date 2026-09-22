package com.aipaas.anycloud.domain.cluster.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GPU 노드인지 못 알아보면 gpu-operator 가 빠진 채로 READY 가 된다.
 *
 * <p>드라이버가 없어 GPU 워크로드는 스케줄되지 않는데 화면은 정상으로 보인다.
 */
class GpuInstanceClassifierTest {

    @ParameterizedTest
    @CsvSource({
        "aws, g5.2xlarge",
        "aws, p4d.24xlarge",
        "gcp, a2-highgpu-1g",
        "gcp, g2-standard-4",
        "alibaba, ecs.gn7i-c8g1.2xlarge",
        "alibaba, ecs.ebmgn8is.32xlarge",
        "ibm, gx2-8x64x1v100",
        "ibm, gx3-16x80x1l4",
        "oci, VM.GPU.A10.1",
        "openstack, 4-8-gpu"
    })
    void gpuTypesAreDetected(String provider, String type) {
        assertThat(GpuInstanceClassifier.isGpu(provider, type)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "aws, t3.large",
        "gcp, e2-standard-2",
        "alibaba, ecs.g9i.large",
        "ibm, bx2-2x8",
        "oci, VM.Standard.E4.Flex",
        "openstack, test-novgpu-2-4-20"
    })
    void plainTypesAreNotGpu(String provider, String type) {
        assertThat(GpuInstanceClassifier.isGpu(provider, type)).isFalse();
    }
}
