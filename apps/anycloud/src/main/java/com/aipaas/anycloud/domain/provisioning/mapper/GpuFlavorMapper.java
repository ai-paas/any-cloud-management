package com.aipaas.anycloud.domain.provisioning.mapper;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code hasGpuNodes=true} 이고 instance type 미지정이면 provider 별 기본 GPU 인스턴스 주입.
 * 운영자 지정 값이 있으면 유지.
 *
 * <p>매핑이 없는 provider 는 경고만 기록하고 값 유지. 임의 선택 시 고비용 인스턴스가 무음으로
 * 생성될 수 있어 Pulumi 검증 또는 운영자 직접 지정에 위임.
 */
@Slf4j
public final class GpuFlavorMapper {

    /** 운영자가 명시한 instance type 으로 override 가능한 key. */
    public static final String CONFIG_KEY_WORKER_INSTANCE_TYPE = "workerInstanceType";

    /** GCP 전용: accelerator type/count 별도 config key (Pulumi GCP provider 가 사용). */
    public static final String CONFIG_KEY_WORKER_ACCELERATOR_TYPE = "workerAcceleratorType";

    public static final String CONFIG_KEY_WORKER_ACCELERATOR_COUNT = "workerAcceleratorCount";

    /** NVIDIA GPU operator 요청 flag. GPU instance 만 생성하면 driver 부재. */
    public static final String CONFIG_KEY_ENABLE_GPU_OPERATOR = "enableGpuOperator";

    private GpuFlavorMapper() {}

    /**
     * provider 별 GPU instance type. 미지원이면 null.
     *
     * <pre>
     * AWS         g5.xlarge       NVIDIA A10G x1 (24GB). Inference / lightweight training
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
     * GPU alias 를 CSP 별 실제 instance type 으로 변환. {@code workerInstanceType=gpu-small} 형태
     * 입력 시 CSP 별 이름 불필요.
     *
     * <p>미지원 조합은 null. 이 경우 instance type 직접 지정 필요.
     *
     * <pre>
     *               gpu-small (T4/L4: inference) | gpu-medium (A10/V100: mid) | gpu-large (A100: training) | gpu-h100 (H100: large training)
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
     * <p>요청 키에는 {@code anycloud-k8s:} 접두 포함. 접두 없이 읽으면 운영자 명시 값을 인식하지
     * 못해 덮어쓰고, 접두 없이 쓰면 동일 설정이 두 키로 분리.
     *
     * @param provider CSP provider (aws/gcp/oci/alibaba 등: 소문자 권장이지만 대소문자 무관 처리)
     * @param config   Pulumi config (mutable copy 전달 권장. 본 메서드가 직접 mutate)
     * @return mutate 발생 여부. true 면 caller 가 log/audit.
     */
    public static String nsKey(String key) {
        return "anycloud-k8s:" + key;
    }

    /** 접두 유무와 무관하게 동일 값으로 조회. */
    private static String read(Map<String, String> config, String key) {
        String value = config.get(nsKey(key));
        return value != null ? value : config.get(key);
    }

    /** 조회 시 발견한 키에 기록. 접두 없는 키가 이미 있으면 해당 키 유지. */
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
            // 운영자가 CSP-agnostic alias (gpu-small / medium / large / h100) 명시: CSP 별 실 instance
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
