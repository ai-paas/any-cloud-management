package com.aipaas.anycloud.domain.provisioning.payload.internal;

import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterNodeResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VmClusterPayloadServiceImpl implements VmClusterPayloadService {

    private static final String CONFIG_MASTER_VM_SPEC = "anycloud-k8s:masterInstanceType";
    private static final String CONFIG_WORKER_VM_SPEC = "anycloud-k8s:workerInstanceType";
    private static final String CONFIG_WORKER_COUNT = "anycloud-k8s:workerCount";
    private static final String CONFIG_ENABLE_INGRESS = "anycloud-k8s:enableIngress";
    private static final String CONFIG_ENABLE_GPU_OPERATOR = "anycloud-k8s:enableGpuOperator";
    private static final String CONFIG_DB_ENABLED = "anycloud-k8s:dbEnabled";
    private static final String CONFIG_KUBERNETES_VERSION = "anycloud-k8s:kubernetesVersion";
    private static final String CONFIG_POD_CIDR = "anycloud-k8s:podCidr";
    private static final String CONFIG_SERVICE_CIDR = "anycloud-k8s:serviceCidr";
    private static final String CONFIG_OPENSTACK_IMAGE_NAME = "anycloud-k8s:openstackImageName";
    private static final String CONFIG_AWS_IMAGE_NAME = "anycloud-k8s:awsImageName";
    private static final String CONFIG_GCP_IMAGE = "anycloud-k8s:gcpImage";
    private static final String CONFIG_AZURE_IMAGE = "anycloud-k8s:azureImage";

    private final ObjectMapper objectMapper;

    @Override
    public String serializeRequestSnapshot(
            ProvisionClusterRequest cluster, ProvisioningRequest request, ResolvedCspCredential credential) {
        Map<String, String> config = cluster.getConfig() == null ? Map.of() : new LinkedHashMap<>(cluster.getConfig());

        VmClusterInternalRequestSnapshot snapshot = VmClusterInternalRequestSnapshot.builder()
                .clusterProvider(request.getProvider())
                .clusterName(cluster.getClusterName())
                .description(cluster.getDescription())
                .environment(cluster.getEnvironment())
                .region(cluster.getRegion())
                .credentialId(credential.getCredentialId())
                .credentialName(credential.getCredentialName())
                .credentialSourceType(
                        credential.getSourceType() == null
                                ? null
                                : credential.getSourceType().name())
                .masterVmSpec(firstNonBlank(
                        config.get(CONFIG_MASTER_VM_SPEC), config.get("anycloud-k8s:openstackFlavorName")))
                .workerVmSpec(firstNonBlank(
                        config.get(CONFIG_WORKER_VM_SPEC), config.get("anycloud-k8s:openstackFlavorName")))
                .workerCount(parseInteger(config.get(CONFIG_WORKER_COUNT), 2))
                .kubernetesVersion(config.get(CONFIG_KUBERNETES_VERSION))
                .podCidr(config.get(CONFIG_POD_CIDR))
                .serviceCidr(config.get(CONFIG_SERVICE_CIDR))
                .osImage(resolveRequestedOsImage(config))
                .enableIngress(parseBoolean(config.get(CONFIG_ENABLE_INGRESS)))
                .enableGpuOperator(parseBoolean(config.get(CONFIG_ENABLE_GPU_OPERATOR)))
                .dbEnabled(parseBoolean(config.get(CONFIG_DB_ENABLED)))
                .providerConfig(config)
                .build();
        return writeJson(snapshot, "Failed to serialize VM cluster request snapshot");
    }

    @Override
    public VmClusterListItemResponse buildListItemResponse(VmClusterEntity vmCluster) {
        Map<String, Object> outputMap = readJsonMap(vmCluster.getRawOutputs());
        VmClusterInternalRequestSnapshot requestSnapshot = readRequestSnapshot(vmCluster.getRequestConfig());

        return VmClusterListItemResponse.builder()
                .id(vmCluster.getId())
                .clusterName(vmCluster.getClusterName())
                .clusterProvider(vmCluster.getClusterProvider())
                .status(vmCluster.getProvisioningStatus().name())
                .statusDetail(vmCluster.getProvisioningStatus().detailMessage())
                .currentWorkflowStep(enumName(vmCluster.getCurrentWorkflowStep()))
                .lastSuccessfulStep(enumName(vmCluster.getLastSuccessfulStep()))
                .lastFailedStep(enumName(vmCluster.getLastFailedStep()))
                .workflowRetryCount(vmCluster.getWorkflowRetryCount())
                .stepStartedAt(resolveStepStartedAt(vmCluster))
                .currentSubStep(vmCluster.getCurrentSubStep())
                .subStepStartedAt(vmCluster.getSubStepStartedAt())
                .lastErrorCode(vmCluster.getLastErrorCode())
                .environment(vmCluster.getEnvironment())
                .region(vmCluster.getRegion())
                .credentialName(firstNonBlank(vmCluster.getCredentialName(), requestSnapshot.getCredentialName()))
                .credentialSourceType(
                        vmCluster.getCredentialSourceType() == null
                                ? requestSnapshot.getCredentialSourceType()
                                : vmCluster.getCredentialSourceType().name())
                .clusterRegistered(vmCluster.getClusterRegistered())
                .masterVmSpec(
                        firstNonBlank(stringValue(outputMap.get("masterVmSpec")), requestSnapshot.getMasterVmSpec()))
                .workerVmSpec(
                        firstNonBlank(stringValue(outputMap.get("workerVmSpec")), requestSnapshot.getWorkerVmSpec()))
                .osImage(firstNonBlank(stringValue(outputMap.get("osImage")), requestSnapshot.getOsImage()))
                .lastError(vmCluster.getLastError())
                .createdAt(vmCluster.getCreatedAt())
                .updatedAt(vmCluster.getUpdatedAt())
                .build();
    }

    @Override
    public VmClusterStatusResponse buildStatusResponse(VmClusterEntity vmCluster) {
        Map<String, Object> outputMap = readJsonMap(vmCluster.getRawOutputs());
        VmClusterInternalRequestSnapshot requestSnapshot = readRequestSnapshot(vmCluster.getRequestConfig());

        return VmClusterStatusResponse.builder()
                .id(vmCluster.getId())
                .clusterName(vmCluster.getClusterName())
                .clusterProvider(vmCluster.getClusterProvider())
                .status(vmCluster.getProvisioningStatus().name())
                .statusDetail(vmCluster.getProvisioningStatus().detailMessage())
                .currentWorkflowStep(enumName(vmCluster.getCurrentWorkflowStep()))
                .lastSuccessfulStep(enumName(vmCluster.getLastSuccessfulStep()))
                .lastFailedStep(enumName(vmCluster.getLastFailedStep()))
                .workflowRetryCount(vmCluster.getWorkflowRetryCount())
                .stackName(vmCluster.getStackName())
                .region(vmCluster.getRegion())
                .environment(vmCluster.getEnvironment())
                .credentialName(firstNonBlank(vmCluster.getCredentialName(), requestSnapshot.getCredentialName()))
                .credentialSourceType(
                        vmCluster.getCredentialSourceType() == null
                                ? requestSnapshot.getCredentialSourceType()
                                : vmCluster.getCredentialSourceType().name())
                .clusterRegistered(vmCluster.getClusterRegistered())
                .lastError(vmCluster.getLastError())
                .bootstrapLog(vmCluster.getBootstrapLog())
                .apiServerUrl(stringValue(outputMap.get("apiServerUrl")))
                .masterPublicIp(stringValue(outputMap.get("masterPublicIp")))
                .masterPublicDns(stringValue(outputMap.get("masterPublicDns")))
                .masterVmSpec(
                        firstNonBlank(stringValue(outputMap.get("masterVmSpec")), requestSnapshot.getMasterVmSpec()))
                .workerVmSpec(
                        firstNonBlank(stringValue(outputMap.get("workerVmSpec")), requestSnapshot.getWorkerVmSpec()))
                .osImage(firstNonBlank(stringValue(outputMap.get("osImage")), requestSnapshot.getOsImage()))
                .dbEndpoint(stringValue(outputMap.get("dbEndpoint")))
                .kubeconfigFetchCommand(stringValue(outputMap.get("kubeconfigFetchCommand")))
                .masterSshCommand(stringValue(outputMap.get("masterSshCommand")))
                .nodes(toVmClusterNodes(outputMap.get("nodes")))
                .createdAt(vmCluster.getCreatedAt())
                .updatedAt(vmCluster.getUpdatedAt())
                .requestedAt(vmCluster.getRequestedAt())
                .provisioningStartedAt(vmCluster.getProvisioningStartedAt())
                .bootstrappingStartedAt(vmCluster.getBootstrappingStartedAt())
                .verifyingStartedAt(vmCluster.getVerifyingStartedAt())
                .readyAt(vmCluster.getReadyAt())
                .failedAt(vmCluster.getFailedAt())
                .deletingStartedAt(vmCluster.getDeletingStartedAt())
                .deletedAt(vmCluster.getDeletedAt())
                .build();
    }

    /**
     * 명시적으로 redaction 대상인 stack output 키 — case-sensitive (Pulumi 출력 키는 camelCase
     * 관례). 사용자 응답 DTO 에 노출되는 필드 (예: masterSshCommand) 는 제외.
     */
    private static final java.util.Set<String> ALWAYS_REDACT_KEYS = java.util.Set.of("sshPrivateKeyPem");

    /**
     * 키 이름에 포함되면 자동 redact — defense-in-depth. 미래에 Pulumi/provider 가 새 secret 필드를
     * 추가하더라도 명시적 화이트리스트 없이 통과 못하게 함. 대소문자 무시.
     *
     * <p>{@code privateKey}, {@code password}, {@code secret}, {@code token}, {@code credential},
     * {@code apiKey}, {@code bearerToken} 중 하나라도 키 이름에 포함되면 redact.
     *
     * <p>예외: 화이트리스트로 사용자 응답에 명시적으로 필요한 키는 별도 제외 (현재 없음).
     */
    private static final java.util.regex.Pattern SECRET_KEY_PATTERN =
            java.util.regex.Pattern.compile("(?i)(privateKey|password|secret|token|credential|apiKey|bearerToken)");

    @Override
    public String serializeSanitizedOutputs(Map<String, Object> outputs) {
        Map<String, Object> sanitized = new LinkedHashMap<>(outputs);
        for (String key : new java.util.ArrayList<>(sanitized.keySet())) {
            if (ALWAYS_REDACT_KEYS.contains(key)
                    || SECRET_KEY_PATTERN.matcher(key).find()) {
                sanitized.put(key, "REDACTED");
            }
        }
        return writeJson(sanitized, "Failed to serialize VM cluster output payload");
    }

    private VmClusterInternalRequestSnapshot readRequestSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return VmClusterInternalRequestSnapshot.builder().build();
        }
        try {
            return objectMapper.readValue(json, VmClusterInternalRequestSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse VM cluster request snapshot", e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse VM cluster output JSON", e);
        }
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(message, e);
        }
    }

    private List<VmClusterNodeResponse> toVmClusterNodes(Object value) {
        if (!(value instanceof List<?> nodes)) {
            return java.util.Collections.emptyList();
        }

        return nodes.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(node -> VmClusterNodeResponse.builder()
                        .role(stringValue(node.get("role")))
                        .instanceId(stringValue(node.get("instanceId")))
                        .publicIp(stringValue(node.get("publicIp")))
                        .privateIp(stringValue(node.get("privateIp")))
                        .publicDns(stringValue(node.get("publicDns")))
                        .ssh(stringValue(node.get("ssh")))
                        .build())
                .toList();
    }

    private String resolveRequestedOsImage(Map<String, String> config) {
        return firstNonBlank(
                config.get(CONFIG_OPENSTACK_IMAGE_NAME),
                config.get(CONFIG_AWS_IMAGE_NAME),
                config.get(CONFIG_GCP_IMAGE),
                config.get(CONFIG_AZURE_IMAGE));
    }

    private Integer parseInteger(String raw, Integer defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Boolean flag 파싱 — strict 검증은 {@code ProvisioningConfigRules.validateBooleanFlags}
     * 에서 끝나므로 여기서는 trim+lowercase 만 적용 (defense-in-depth). 검증을 우회한
     * 경로로 들어오면 "true" / "false" 외엔 모두 null 반환.
     */
    private Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null; // validation 우회 케이스 — silent false 회피.
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** currentWorkflowStep 에 해당하는 step 시작 timestamp. step 없으면 null. */
    private static java.time.LocalDateTime resolveStepStartedAt(VmClusterEntity v) {
        if (v.getCurrentWorkflowStep() == null) {
            return null;
        }
        return switch (v.getCurrentWorkflowStep()) {
            case PROVISION -> v.getProvisioningStartedAt();
            case BOOTSTRAP -> v.getBootstrappingStartedAt();
            case VERIFY -> v.getVerifyingStartedAt();
            case DESTROY -> v.getDeletingStartedAt();
        };
    }
}
