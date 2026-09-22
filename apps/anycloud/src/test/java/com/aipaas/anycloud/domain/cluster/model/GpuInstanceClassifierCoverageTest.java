package com.aipaas.anycloud.domain.cluster.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GPU 를 못 알아보면 드라이버 스택이 올라가지 않는다.
 *
 * <p>아래 타입은 모두 해당 계정에서 실제로 조회된 것들이다.
 */
class GpuInstanceClassifierCoverageTest extends AbstractUnitTest {

    @ParameterizedTest
    @CsvSource({
        "aws, g6.24xlarge",
        "gcp, a2-highgpu-1g",
        "oci, VM.GPU.A10.1",
        "alibaba, ecs.gn7i-c8g1.2xlarge",
        "ibm, gx3-16x80x1l4",
        "ibm, gx3d-160x1792x8h100",
        "openstack, 32-256-gpu",
        "openstack, 16-64-gpu-2EA",
        "openstack, hybrid-mig-8-16-50-vgpu2"
    })
    void gpuTypesAreRecognised(String provider, String instanceType) {
        assertThat(GpuInstanceClassifier.isGpu(provider, instanceType)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "aws, t3.large",
        "gcp, e2-standard-2",
        "oci, VM.Standard2.2",
        "alibaba, ecs.g9i.large",
        "ibm, bx2-2x8",
        "openstack, 4-8-50",
        "proxmox, 2-4096"
    })
    void ordinaryTypesAreNotMistakenForGpu(String provider, String instanceType) {
        assertThat(GpuInstanceClassifier.isGpu(provider, instanceType)).isFalse();
    }

    @Test
    void missingValuesAreNotGpu() {
        assertThat(GpuInstanceClassifier.isGpu(null, "g6.24xlarge")).isFalse();
        assertThat(GpuInstanceClassifier.isGpu("aws", null)).isFalse();
        assertThat(GpuInstanceClassifier.isGpu("aws", "  ")).isFalse();
    }
}
