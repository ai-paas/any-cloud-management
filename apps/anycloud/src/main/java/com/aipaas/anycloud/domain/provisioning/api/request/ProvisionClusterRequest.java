package com.aipaas.anycloud.domain.provisioning.api.request;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Pulumi 기반 클러스터 프로비저닝 요청 DTO. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionClusterRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 8286203654928238094L;

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.PROVIDER_PATTERN,
            message = "clusterProvider must match " + ApiValidationConstants.PROVIDER_PATTERN)
    @Schema(description = "클러스터 공급자 (AWS, GCP, Azure, OpenStack 등)", example = "AWS")
    private String clusterProvider;

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.K8S_NAME_PATTERN,
            message = "clusterName must be RFC 1123 label (lowercase a-z, 0-9, '-')")
    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
    @Schema(description = "생성할 클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Size(max = ApiValidationConstants.DESCRIPTION_MAX)
    @Schema(description = "클러스터 설명", example = "AWS development cluster")
    private String description;

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.ENVIRONMENT_PATTERN,
            message = "environment must match " + ApiValidationConstants.ENVIRONMENT_PATTERN)
    @Schema(description = "배포 환경명", example = "dev")
    private String environment;

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.REGION_PATTERN,
            message = "region must match " + ApiValidationConstants.REGION_PATTERN)
    @Schema(description = "리전 정보", example = "ap-northeast-2")
    private String region;

    @Pattern(
            regexp = ApiValidationConstants.CREDENTIAL_ID_PATTERN,
            message = "credentialId must match " + ApiValidationConstants.CREDENTIAL_ID_PATTERN)
    @Schema(description = "사전 등록된 CSP 자격증명 ID", example = "cred-001")
    private String credentialId;

    @Schema(
            description = "Pulumi config으로 전달할 추가 key/value",
            example =
                    "{\"masterInstanceType\":\"t3.large\",\"workerInstanceType\":\"t3.large\",\"workerCount\":\"2\",\"imageName\":\"ubuntu-24.04\"}")
    private Map<String, String> config;

    /**
     * GPU 노드 포함 여부. true 면 Pulumi 가 GPU flavor 워커 노드를 프로비저닝 (workerInstanceType 등을
     * CSP 의 GPU 인스턴스로 적용). cluster ACTIVE 시 dcgm-exporter 도 자동 설치 (cluster-observability).
     */
    @Schema(description = "GPU 노드 포함 cluster", example = "false", defaultValue = "false")
    private Boolean hasGpuNodes;
}
