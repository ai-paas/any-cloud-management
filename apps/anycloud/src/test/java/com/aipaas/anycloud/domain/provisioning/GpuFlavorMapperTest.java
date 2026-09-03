package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.mapper.GpuFlavorMapper;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Provider 별 GPU instance type 자동 매핑 회귀.
 */
class GpuFlavorMapperTest extends AbstractUnitTest {

    @Test
    void applyGpuDefaults_aws_injectsG5XlargeAndEnablesGpuOperator() {
        Map<String, String> cfg = new HashMap<>();
        boolean mutated = GpuFlavorMapper.applyGpuDefaults("aws", cfg);
        assertThat(mutated).isTrue();
        assertThat(cfg)
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "g5.xlarge")
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_ENABLE_GPU_OPERATOR, "true");
    }

    @Test
    void applyGpuDefaults_operatorDisabledGpuOperator_isPreserved() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put(GpuFlavorMapper.CONFIG_KEY_ENABLE_GPU_OPERATOR, "false");
        GpuFlavorMapper.applyGpuDefaults("aws", cfg);
        assertThat(cfg).containsEntry(GpuFlavorMapper.CONFIG_KEY_ENABLE_GPU_OPERATOR, "false");
    }

    @Test
    void applyGpuDefaults_gcp_injectsAcceleratorTypeAndCount() {
        Map<String, String> cfg = new HashMap<>();
        boolean mutated = GpuFlavorMapper.applyGpuDefaults("gcp", cfg);
        assertThat(mutated).isTrue();
        assertThat(cfg)
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "n1-standard-4")
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_TYPE, "nvidia-tesla-t4")
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_COUNT, "1");
    }

    @Test
    void applyGpuDefaults_azure_injectsNcv3() {
        Map<String, String> cfg = new HashMap<>();
        GpuFlavorMapper.applyGpuDefaults("azure", cfg);
        assertThat(cfg).containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "Standard_NC4as_T4_v3");
    }

    @Test
    void applyGpuDefaults_oci_injectsA10() {
        Map<String, String> cfg = new HashMap<>();
        GpuFlavorMapper.applyGpuDefaults("oci", cfg);
        assertThat(cfg).containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "VM.GPU.A10.1");
    }

    @Test
    void applyGpuDefaults_operatorSpecified_keepsValue() {
        // 운영자가 명시한 instance type 은 override 안 함.
        Map<String, String> cfg = new HashMap<>();
        cfg.put(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "p4d.24xlarge");
        GpuFlavorMapper.applyGpuDefaults("aws", cfg);
        assertThat(cfg).containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "p4d.24xlarge");
    }

    @Test
    void applyGpuDefaults_caseInsensitiveProvider() {
        Map<String, String> cfg = new HashMap<>();
        GpuFlavorMapper.applyGpuDefaults("AWS", cfg);
        assertThat(cfg).containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "g5.xlarge");
    }

    @Test
    void applyGpuDefaults_unsupportedProvider_returnsFalseNoMutation() {
        Map<String, String> cfg = new HashMap<>();
        boolean mutated = GpuFlavorMapper.applyGpuDefaults("digitalocean", cfg);
        assertThat(mutated).isFalse();
        assertThat(cfg).isEmpty();
    }

    @Test
    void applyGpuDefaults_nullConfig_returnsFalseSafely() {
        boolean mutated = GpuFlavorMapper.applyGpuDefaults("aws", null);
        assertThat(mutated).isFalse();
    }

    @Test
    void applyGpuDefaults_emptyProvider_returnsFalseSafely() {
        boolean mutated = GpuFlavorMapper.applyGpuDefaults("", new HashMap<>());
        assertThat(mutated).isFalse();
    }

    @Test
    void applyGpuDefaults_gcp_operatorAcceleratorOverridesKept() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_TYPE, "nvidia-tesla-a100");
        cfg.put(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_COUNT, "8");
        GpuFlavorMapper.applyGpuDefaults("gcp", cfg);
        // 운영자 값 보존.
        assertThat(cfg)
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_TYPE, "nvidia-tesla-a100")
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_ACCELERATOR_COUNT, "8")
                // instance type 은 default 주입 (운영자 미명시).
                .containsEntry(GpuFlavorMapper.CONFIG_KEY_WORKER_INSTANCE_TYPE, "n1-standard-4");
    }
}
