package com.aipaas.anycloud.domain.provisioning.mapper;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * CSP provider 별 GPU flavor (instance type) 자동 매핑.
 *
 * <p>{@link com.aipaas.anycloud.domain.cluster.model.VmClusterSpec} 의 {@code hasGpuNodes=true} +
 * {@code config.workerInstanceType} 미명시 시점에 본 mapper 가 provider 의 sensible default GPU 인스턴스
 * 를 주입.
 *
 * <p>각 매핑은 합리적 가성비 + 보편적 가용성 기준. 운영자가 명시한 instance type 은 항상 우선 (override).
 *
 * <p>매핑 부재 / unsupported provider 는 warning log + 변경 없음 — Pulumi 가 config 검증 시 실패하거나
 * 운영자가 직접 instance type 지정 필요.
 */
@Slf4j
public final class GpuFlavorMapper {

    /** 운영자가 명시한 instance type 으로 override 가능한 key. */
    public static final String CONFIG_KEY_WORKER_INSTANCE_TYPE = "workerInstanceType";

    /** GCP 전용 — accelerator type/count 별도 config key (Pulumi GCP provider 가 사용). */
    public static final String CONFIG_KEY_WORKER_ACCELERATOR_TYPE = "workerAcceleratorType";

    public static final String CONFIG_KEY_WORKER_ACCELERATOR_COUNT = "workerAcceleratorCount";

    /**
     * NVIDIA GPU operator 요청 flag. GPU instance 만 띄우면 driver 가 없다.
     *
     * <p>Pulumi 는 이 값을 읽지 않는다 — VM 과 네트워크까지가 Pulumi 의 범위다. true 면
     * {@code nvidia-gpu-operator} addon 이 등록되고, agent 연결 후 helm 으로 설치되며
     * operator 가 driver / container runtime / device plugin 을 관리한다.
     *
     * <p>dcgm-exporter 는 별도 addon 이다 — operator 가 노드 측 driver/runtime 을 담당하고
     * dcgm-exporter 가 메트릭을 노출한다. 카탈로그가 operator 쪽 dcgmExporter 를 꺼서 중복을 피한다.
     */
    public static final String CONFIG_KEY_ENABLE_GPU_OPERATOR = "enableGpuOperator";

    private GpuFlavorMapper() {}

    /**
     * provider 별 GPU instance type. 미지원이면 null.
     *
     * <pre>
     * AWS         g5.xlarge       NVIDIA A10G x1 (24GB) — Inference / lightweight training
     * GCP         n1-standard-4 + accelerator nvidia-tesla-t4 x1
     * Azure       Standard_NC4as_T4_v3   NVIDIA T4 x1 (16GB)
     * OCI         VM.GPU.A10.1    NVIDIA A10 x1 (24GB)
     * Alibaba     ecs.gn6i-c4g1.xlarge   NVIDIA T4 x1
     * </pre>
     */
    private static final Map<String, String> DEFAULT_GPU_INSTANCE = Map.of(
            "aws", "g5.xlarge",
            "gcp", "n1-standard-4",
            "azure", "Standard_NC4as_T4_v3",
            "oci", "VM.GPU.A10.1",
            "alibaba", "ecs.gn6i-c4g1.xlarge");

    /**
     * GCP 는 accelerator 가 별도 attach. 기본값 = NVIDIA T4 x1.
     */
    private static final Map<String, Map<String, String>> EXTRA_GPU_CONFIG = Map.of(
            "gcp",
            Map.of(
                    CONFIG_KEY_WORKER_ACCELERATOR_TYPE, "nvidia-tesla-t4",
                    CONFIG_KEY_WORKER_ACCELERATOR_COUNT, "1"));

    /**
     * CSP-agnostic GPU alias → provider × alias → 실 instance type 매트릭스.
     *
     * <p>운영자가 workerInstanceType=gpu-small/medium/large/h100 으로 명시하면 본 매핑 적용.
     * CSP 별 진짜 instance type 명을 모르고도 workload 단위로 cluster 생성 가능.
     *
     * <p>매핑 부재 (지원 안 하는 CSP × alias 조합) 는 null 반환 — 운영자가 CSP 별 instance type
     * 직접 명시 필요.
     *
     * <pre>
     *               gpu-small (T4/L4 — inference) | gpu-medium (A10/V100 — mid) | gpu-large (A100 — training) | gpu-h100 (H100 — large training)
     * AWS           g4dn.xlarge                    g5.xlarge                     p4d.24xlarge                  p5.48xlarge
     * GCP           n1-standard-4 (T4)             g2-standard-4 (L4)            a2-highgpu-1g (A100)          a3-highgpu-8g (H100 8x)
     * Azure         Standard_NC4as_T4_v3           Standard_NC6s_v3 (V100)       Standard_NC24ads_A100_v4      Standard_ND96isr_H100_v5
     * OCI           VM.GPU3.1 (V100)               VM.GPU.A10.1                  BM.GPU.A100-v2.8              BM.GPU.H100.8
     * Alibaba       ecs.gn6i-c4g1.xlarge (T4)      ecs.gn7i-c8g1.2xlarge (A10)   ecs.ebmgn7e.32xlarge (A100)   (미지원)
     * </pre>
     */
    private static final Map<String, Map<String, String>> GPU_ALIAS_INSTANCE = Map.of(
            "aws",
                    Map.of(
                            "gpu-small", "g4dn.xlarge",
                            "gpu-medium", "g5.xlarge",
                            "gpu-large", "p4d.24xlarge",
                            "gpu-h100", "p5.48xlarge"),
            "gcp",
                    Map.of(
                            "gpu-small", "n1-standard-4",
                            "gpu-medium", "g2-standard-4",
                            "gpu-large", "a2-highgpu-1g",
                            "gpu-h100", "a3-highgpu-8g"),
            "azure",
                    Map.of(
                            "gpu-small", "Standard_NC4as_T4_v3",
                            "gpu-medium", "Standard_NC6s_v3",
                            "gpu-large", "Standard_NC24ads_A100_v4",
                            "gpu-h100", "Standard_ND96isr_H100_v5"),
            "oci",
                    Map.of(
                            "gpu-small", "VM.GPU3.1",
                            "gpu-medium", "VM.GPU.A10.1",
                            "gpu-large", "BM.GPU.A100-v2.8",
                            "gpu-h100", "BM.GPU.H100.8"),
            "alibaba",
                    Map.of(
                            "gpu-small", "ecs.gn6i-c4g1.xlarge",
                            "gpu-medium", "ecs.gn7i-c8g1.2xlarge",
                            "gpu-large", "ecs.ebmgn7e.32xlarge"));

    /**
     * GCP 의 alias 별 accelerator type / count override.
     *
     * <p>gpu-small (T4) 는 default 와 동일 — entry 없음.
     * gpu-medium (L4) / gpu-large (A100) / gpu-h100 은 instance type 자체에 accelerator 포함 — extra
     * accelerator config 불요 (n1 family 만 별도 attach 필요).
     */
    private static final Map<String, Map<String, String>> GCP_ALIAS_EXTRA = Map.of(
            "gpu-small",
            Map.of(
                    CONFIG_KEY_WORKER_ACCELERATOR_TYPE, "nvidia-tesla-t4",
                    CONFIG_KEY_WORKER_ACCELERATOR_COUNT, "1"));

    /**
     * 운영자가 workerInstanceType 으로 alias (gpu-small/medium/large/h100) 를 명시했으면 CSP 별 실
     * instance type 으로 변환. alias 아닌 명시 instance type 은 그대로 반환 (override 보존).
     *
     * @return resolved 실 instance type, 또는 null (지원 안 하는 CSP × alias 조합)
     */
    public static String resolveAlias(String provider, String workerInstanceType) {
        if (workerInstanceType == null || workerInstanceType.isBlank() || provider == null) {
            return workerInstanceType;
        }
        String input = workerInstanceType.trim();
        if (!input.startsWith("gpu-")) {
            return workerInstanceType;
        }
        String key = provider.toLowerCase().trim();
        Map<String, String> aliasMap = GPU_ALIAS_INSTANCE.get(key);
        if (aliasMap == null) {
            log.warn("GpuFlavorMapper.resolveAlias: provider={} unsupported for GPU alias", provider);
            return null;
        }
        String resolved = aliasMap.get(input);
        if (resolved == null) {
            log.warn(
                    "GpuFlavorMapper.resolveAlias: provider={} alias={} not available (CSP × alias matrix gap)",
                    provider,
                    input);
        }
        return resolved;
    }

    /**
     * hasGpuNodes=true 일 때 호출. provided config 에 workerInstanceType 이 없으면 provider default
     * 주입. 이미 있으면 변경 없음 (운영자 명시 우선).
     *
     * @param provider CSP provider (aws/gcp/azure/oci/alibaba 등 — 소문자 권장이지만 대소문자 무관 처리)
     * @param config   Pulumi config (mutable copy 전달 권장 — 본 메서드가 직접 mutate)
     * @return mutate 발생 여부. true 면 caller 가 log/audit.
     */
    public static boolean applyGpuDefaults(String provider, Map<String, String> config) {
        if (config == null || provider == null || provider.isBlank()) {
            return false;
        }
        String key = provider.toLowerCase().trim();
        String defaultInstance = DEFAULT_GPU_INSTANCE.get(key);
        if (defaultInstance == null) {
            log.warn(
                    "GpuFlavorMapper: no default GPU instance for provider={} — operator must specify "
                            + "workerInstanceType in config",
                    provider);
            return false;
        }
        boolean mutated = false;

        String existing = config.get(CONFIG_KEY_WORKER_INSTANCE_TYPE);
        String aliasResolved = null;
        if (existing != null && !existing.isBlank() && existing.startsWith("gpu-")) {
            // 운영자가 CSP-agnostic alias (gpu-small / medium / large / h100) 명시 — CSP 별 실 instance
            // type 으로 변환. 변환 실패 시 default GPU instance 로 fallback.
            aliasResolved = resolveAlias(provider, existing);
            if (aliasResolved != null) {
                config.put(CONFIG_KEY_WORKER_INSTANCE_TYPE, aliasResolved);
                log.info(
                        "GpuFlavorMapper: provider={} alias={} → {} (auto-resolved)",
                        provider,
                        existing,
                        aliasResolved);
                mutated = true;
                // GCP 의 alias 별 accelerator override (T4 alias 만 extra config 적용, 다른 alias 는
                // instance type 자체에 accelerator 포함).
                if ("gcp".equals(key)) {
                    Map<String, String> aliasExtra = GCP_ALIAS_EXTRA.get(existing);
                    if (aliasExtra != null) {
                        for (Map.Entry<String, String> e : aliasExtra.entrySet()) {
                            config.put(e.getKey(), e.getValue());
                            log.info(
                                    "GpuFlavorMapper: gcp alias={} → {}={} (auto)", existing, e.getKey(), e.getValue());
                        }
                    }
                }
            } else {
                config.put(CONFIG_KEY_WORKER_INSTANCE_TYPE, defaultInstance);
                log.warn(
                        "GpuFlavorMapper: provider={} alias={} unresolvable → default={}",
                        provider,
                        existing,
                        defaultInstance);
                mutated = true;
            }
        } else if (existing == null || existing.isBlank()) {
            config.put(CONFIG_KEY_WORKER_INSTANCE_TYPE, defaultInstance);
            log.info("GpuFlavorMapper: provider={} → workerInstanceType={} (auto)", provider, defaultInstance);
            mutated = true;
        } else {
            log.debug(
                    "GpuFlavorMapper: provider={} workerInstanceType={} (operator-specified, kept)",
                    provider,
                    existing);
        }

        // 운영자가 명시적으로 끄지 않는 한 GPU cluster 는 driver/runtime 이 반드시 필요하므로 default true.
        String gpuOpExisting = config.get(CONFIG_KEY_ENABLE_GPU_OPERATOR);
        if (gpuOpExisting == null || gpuOpExisting.isBlank()) {
            config.put(CONFIG_KEY_ENABLE_GPU_OPERATOR, "true");
            log.info("GpuFlavorMapper: provider={} → enableGpuOperator=true (auto)", provider);
            mutated = true;
        }

        Map<String, String> extras = EXTRA_GPU_CONFIG.get(key);
        if (extras != null) {
            for (Map.Entry<String, String> e : extras.entrySet()) {
                if (config.get(e.getKey()) == null || config.get(e.getKey()).isBlank()) {
                    config.put(e.getKey(), e.getValue());
                    log.info("GpuFlavorMapper: provider={} → {}={} (auto)", provider, e.getKey(), e.getValue());
                    mutated = true;
                }
            }
        }
        return mutated;
    }
}
