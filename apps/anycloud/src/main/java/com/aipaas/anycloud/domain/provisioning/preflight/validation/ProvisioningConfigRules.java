package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ProvisioningConfigRules {

    private static final String MASTER_VM_SPEC = "anycloud-k8s:masterInstanceType";
    private static final String WORKER_VM_SPEC = "anycloud-k8s:workerInstanceType";
    private static final String MASTER_COUNT = "anycloud-k8s:masterCount";
    private static final String WORKER_COUNT = "anycloud-k8s:workerCount";
    private static final String KUBERNETES_VERSION = "anycloud-k8s:kubernetesVersion";
    private static final String POD_CIDR = "anycloud-k8s:podCidr";
    private static final String SERVICE_CIDR = "anycloud-k8s:serviceCidr";
    private static final String JOIN_TOKEN = "anycloud-k8s:joinToken";
    private static final String ENABLE_INGRESS = "anycloud-k8s:enableIngress";
    private static final String ENABLE_GPU_OPERATOR = "anycloud-k8s:enableGpuOperator";
    private static final String DB_ENABLED = "anycloud-k8s:dbEnabled";

    /**
     * Boolean 의미 키들 — strict parser 대상. {@link Boolean#parseBoolean} 의 silent
     * false (대문자 "True", "1", "yes" → false) 이 발견-안 되는 오타를 차단.
     */
    private static final List<String> BOOLEAN_FLAG_KEYS = List.of(ENABLE_INGRESS, ENABLE_GPU_OPERATOR, DB_ENABLED);

    private ProvisioningConfigRules() {}

    public static void applyDefaults(SupportedProvisioningProvider provider, Map<String, String> config) {
        switch (provider) {
            case GCP -> {
                config.putIfAbsent(MASTER_VM_SPEC, "e2-standard-2");
                config.putIfAbsent(WORKER_VM_SPEC, "e2-standard-2");
            }
            case AZURE -> {
                config.putIfAbsent(MASTER_VM_SPEC, "Standard_D4s_v5");
                config.putIfAbsent(WORKER_VM_SPEC, "Standard_D4s_v5");
            }
            case ALIBABA -> {
                config.putIfAbsent(MASTER_VM_SPEC, "ecs.g6.large");
                config.putIfAbsent(WORKER_VM_SPEC, "ecs.g6.large");
            }
            case OPENSTACK -> {
                config.putIfAbsent("anycloud-k8s:openstackImageName", "ubuntu-24.04");
                config.putIfAbsent("anycloud-k8s:openstackFlavorName", "m1.large");
                config.putIfAbsent(MASTER_VM_SPEC, config.get("anycloud-k8s:openstackFlavorName"));
                config.putIfAbsent(WORKER_VM_SPEC, config.get("anycloud-k8s:openstackFlavorName"));
            }
            case OCI -> {
                config.putIfAbsent(MASTER_VM_SPEC, "VM.Standard.E4.Flex");
                config.putIfAbsent(WORKER_VM_SPEC, "VM.Standard.E4.Flex");
            }
            case DIGITALOCEAN -> {
                config.putIfAbsent(MASTER_VM_SPEC, "s-2vcpu-4gb");
                config.putIfAbsent(WORKER_VM_SPEC, "s-2vcpu-4gb");
            }
            default -> {
                config.putIfAbsent(MASTER_VM_SPEC, "t3.large");
                config.putIfAbsent(WORKER_VM_SPEC, "t3.large");
            }
        }

        config.putIfAbsent(MASTER_COUNT, "1");
        config.putIfAbsent(WORKER_COUNT, "2");
        config.putIfAbsent(KUBERNETES_VERSION, "1.31");
        config.putIfAbsent(POD_CIDR, "192.168.0.0/16");
        config.putIfAbsent(SERVICE_CIDR, "10.96.0.0/12");
        // 정상 경로 (VmClusterProviderImpl.toProvisionDto) 는 항상 backend 생성 token 을 set —
        // 여기 도달 시점엔 이미 존재. 우회 경로 (직접 ProvisionClusterRequest 구성) 대비 방어선으로
        // 하드코딩 공유 token 대신 random 생성.
        config.putIfAbsent(JOIN_TOKEN, com.aipaas.anycloud.domain.provisioning.bootstrap.KubeadmJoinTokens.generate());
        config.putIfAbsent(ENABLE_INGRESS, "false");
        config.putIfAbsent(ENABLE_GPU_OPERATOR, "false");
    }

    public static void validateRequiredConfig(SupportedProvisioningProvider provider, Map<String, String> config) {
        List<String> missingKeys = new ArrayList<>();

        // MasterCount sanity — etcd quorum 요구사항으로 odd-only, 합리적 범위 1..7 강제.
        validateMasterCount(config);

        // Boolean 키 strict — Boolean.parseBoolean 의 silent-false 회피.
        validateBooleanFlags(config);

        switch (provider) {
            case GCP -> requireConfigKeys(config, missingKeys, "anycloud-k8s:gcpProject");
            case AZURE -> requireConfigKeys(config, missingKeys, "anycloud-k8s:azureResourceGroup");
            case OPENSTACK -> {
                requireConfigKeys(
                        config, missingKeys, "anycloud-k8s:openstackImageName", "anycloud-k8s:openstackFlavorName");
                requireAnyConfigKey(
                        config,
                        missingKeys,
                        List.of("anycloud-k8s:openstackExternalNetworkId", "anycloud-k8s:openstackFloatingIpPool"));
            }
            case OCI -> requireConfigKeys(config, missingKeys, "anycloud-k8s:ociCompartmentId");
            default -> {}
        }

        if (!missingKeys.isEmpty()) {
            throw new CustomException(
                    "Missing required provisioning config: " + String.join(", ", missingKeys),
                    ErrorCode.PROVISIONING_CONFIG_MISSING_KEY);
        }
    }

    private static void validateMasterCount(Map<String, String> config) {
        String raw = config.get(MASTER_COUNT);
        if (raw == null || raw.isBlank()) {
            return; // putIfAbsent in applyDefaults — should not reach here.
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new CustomException(
                    "masterCount 는 정수여야 합니다. 입력값: '" + raw + "'. 허용값: 1, 3, 5, 7 (단일 master 는 1).",
                    ErrorCode.PROVISIONING_CONFIG_INVALID_VALUE);
        }
        if (value < 1 || value > 7) {
            throw new CustomException(
                    "masterCount 는 1~7 범위여야 합니다. 입력값: " + value + ". 권장: 단일 master 는 1, HA 는 3 (5/7 은 대규모 HA 전용).",
                    ErrorCode.PROVISIONING_CONFIG_INVALID_VALUE);
        }
        if (value % 2 == 0) {
            throw new CustomException(
                    "masterCount 는 1, 3, 5, 7 중 하나여야 합니다 (HA control-plane 은 홀수 — etcd quorum 요구). " + "입력값: " + value
                            + ". 권장: " + (value + 1) + ".",
                    ErrorCode.PROVISIONING_CONFIG_INVALID_VALUE);
        }
    }

    private static void validateBooleanFlags(Map<String, String> config) {
        for (String key : BOOLEAN_FLAG_KEYS) {
            String raw = config.get(key);
            if (raw == null) {
                continue;
            }
            String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isEmpty()) {
                continue; // unset; default 가 applyDefaults 에서 적용됨.
            }
            if (!"true".equals(normalized) && !"false".equals(normalized)) {
                throw new CustomException(
                        key + " must be 'true' or 'false' (case-insensitive). 입력값: '" + raw + "'",
                        ErrorCode.PROVISIONING_CONFIG_INVALID_VALUE);
            }
        }
    }

    private static void requireConfigKeys(Map<String, String> config, List<String> missing, String... keys) {
        for (String key : keys) {
            if (!config.containsKey(key)
                    || config.get(key) == null
                    || config.get(key).isBlank()) {
                missing.add(key);
            }
        }
    }

    private static void requireAnyConfigKey(Map<String, String> config, List<String> missing, List<String> candidates) {
        boolean present = candidates.stream()
                .anyMatch(key -> config.containsKey(key)
                        && config.get(key) != null
                        && !config.get(key).isBlank());
        if (!present) {
            missing.add(String.join(" or ", candidates));
        }
    }
}
