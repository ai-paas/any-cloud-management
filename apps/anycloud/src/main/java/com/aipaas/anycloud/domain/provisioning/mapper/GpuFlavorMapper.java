package com.aipaas.anycloud.domain.provisioning.mapper;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code hasGpuNodes=true} 인데 instance type 을 주지 않으면 provider 별 기본 GPU 인스턴스를 넣습니다.
 * 운영자가 지정한 값이 있으면 건드리지 않습니다.
 *
 * <p>매핑이 없는 provider 는 경고만 남기고 그대로 둡니다. 임의로 고르면 비싼 인스턴스가 조용히
 * 뜰 수 있어, 그때는 Pulumi 검증에서 걸리거나 운영자가 직접 지정하게 둡니다.
 */
@Slf4j
public final class GpuFlavorMapper {

    /** 운영자가 명시한 instance type 으로 override 가능한 key. */
    public static final String CONFIG_KEY_WORKER_INSTANCE_TYPE = "workerInstanceType";

    /** GCP 전용 — accelerator type/count 별도 config key (Pulumi GCP provider 가 사용). */
    public static final String CONFIG_KEY_WORKER_ACCELERATOR_TYPE = "workerAcceleratorType";

    public static final String CONFIG_KEY_WORKER_ACCELERATOR_COUNT = "workerAcceleratorCount";

    /** NVIDIA GPU operator 요청 flag. GPU instance 만 띄우면 driver 가 없다. */
    public static final String CONFIG_KEY_ENABLE_GPU_OPERATOR = "enableGpuOperator";

    private GpuFlavorMapper() {}

    /**
     * provider 별 GPU instance type. 미지원이면 null.
     *
     * <pre>
     * AWS         g5.xlarge       NVIDIA A10G x1 (24GB) — Inference / lightweight training
     * GCP         n1-standard-4 + accelerator nvidia-tesla-t4 x1
     * OCI         VM.GPU.A10.1    NVIDIA A10 x1 (24GB)
     * Alibaba     ecs.gn6i-c4g1.xlarge   NVIDIA T4 x1
     * </pre>
     */
    private static final Map<String, String> DEFAULT_GPU_INSTANCE = Map.of(
            "aws", "g5.xlarge",
            "gcp", "n1-standard-4",
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
     * GPU alias 를 CSP 별 실제 instance type 으로 옮깁니다. 운영자가
     * {@code workerInstanceType=gpu-small} 처럼 적으면 CSP 별 이름을 몰라도 됩니다.
     *
     * <p>지원하지 않는 조합은 null 입니다. 그때는 instance type 을 직접 적어야 합니다.
     *
     * <pre>
     *               gpu-small (T4/L4 — inference) | gpu-medium (A10/V100 — mid) | gpu-large (A100 — training) | gpu-h100 (H100 — large training)
     * AWS           g4dn.xlarge                    g5.xlarge                     p4d.24xlarge                  p5.48xlarge
     * GCP           n1-standard-4 (T4)             g2-standard-4 (L4)            a2-highgpu-1g (A100)          a3-highgpu-8g (H100 8x)
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

    /** GCP 의 alias 별 accelerator type / count override. */
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
     * @param provider CSP provider (aws/gcp/oci/alibaba 등 — 소문자 권장이지만 대소문자 무관 처리)
     * @param config   Pulumi config (mutable copy 전달 권장 — 본 메서드가 직접 mutate)
     * @return mutate 발생 여부. true 면 caller 가 log/audit.
     */
    /**
     * 요청은 {@code anycloud-k8s:} 접두를 붙여 온다. 접두 없이 읽으면 운영자가 명시한 값을 못 보고
     * 덮어쓰고, 접두 없이 쓰면 같은 설정이 두 키로 갈린다.
     */
    public static String nsKey(String key) {
        return "anycloud-k8s:" + key;
    }

    /** 접두가 있든 없든 같은 값으로 읽는다. */
    private static String read(Map<String, String> config, String key) {
        String value = config.get(nsKey(key));
        return value != null ? value : config.get(key);
    }

    /** 읽을 때 찾은 자리에 쓴다. 접두 없는 값이 이미 있으면 그 자리를 유지한다. */
    private static void write(Map<String, String> config, String key, String value) {
        if (config.containsKey(key)) {
            config.put(key, value);
        } else {
            config.put(nsKey(key), value);
        }
    }

    /** 운영자가 명시적으로 끄지 않는 한 GPU cluster 는 driver/runtime 이 반드시 필요하다. */
    private static boolean enableGpuOperatorByDefault(Map<String, String> config) {
        String existing = read(config, CONFIG_KEY_ENABLE_GPU_OPERATOR);
        if (existing != null && !existing.isBlank()) {
            return false;
        }
        write(config, CONFIG_KEY_ENABLE_GPU_OPERATOR, "true");
        return true;
    }

    public static boolean applyGpuDefaults(String provider, Map<String, String> config) {
        if (config == null || provider == null || provider.isBlank()) {
            return false;
        }
        String key = provider.toLowerCase().trim();
        /*
         * operator 기본값은 flavor 표보다 먼저 정한다. 표에 없는 CSP(IBM, OpenStack)가 이 아래에서
         * 되돌아가면서 플래그까지 빠져, GPU 노드가 드라이버 없이 READY 가 됐다.
         */
        boolean mutated = enableGpuOperatorByDefault(config);

        String defaultInstance = DEFAULT_GPU_INSTANCE.get(key);
        if (defaultInstance == null) {
            log.warn(
                    "GpuFlavorMapper: no default GPU instance for provider={} — operator must specify "
                            + "workerInstanceType in config",
                    provider);
            return mutated;
        }

        String existing = read(config, CONFIG_KEY_WORKER_INSTANCE_TYPE);
        String aliasResolved = null;
        if (existing != null && !existing.isBlank() && existing.startsWith("gpu-")) {
            // 운영자가 CSP-agnostic alias (gpu-small / medium / large / h100) 명시 — CSP 별 실 instance
            // type 으로 변환. 변환 실패 시 default GPU instance 로 fallback.
            aliasResolved = resolveAlias(provider, existing);
            if (aliasResolved != null) {
                write(config, CONFIG_KEY_WORKER_INSTANCE_TYPE, aliasResolved);
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
                            write(config, e.getKey(), e.getValue());
                            log.info(
                                    "GpuFlavorMapper: gcp alias={} → {}={} (auto)", existing, e.getKey(), e.getValue());
                        }
                    }
                }
            } else {
                write(config, CONFIG_KEY_WORKER_INSTANCE_TYPE, defaultInstance);
                log.warn(
                        "GpuFlavorMapper: provider={} alias={} unresolvable → default={}",
                        provider,
                        existing,
                        defaultInstance);
                mutated = true;
            }
        } else if (existing == null || existing.isBlank()) {
            write(config, CONFIG_KEY_WORKER_INSTANCE_TYPE, defaultInstance);
            log.info("GpuFlavorMapper: provider={} → workerInstanceType={} (auto)", provider, defaultInstance);
            mutated = true;
        } else {
            log.debug(
                    "GpuFlavorMapper: provider={} workerInstanceType={} (operator-specified, kept)",
                    provider,
                    existing);
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
