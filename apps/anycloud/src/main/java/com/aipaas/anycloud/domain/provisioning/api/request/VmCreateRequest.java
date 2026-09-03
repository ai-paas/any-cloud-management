package com.aipaas.anycloud.domain.provisioning.api.request;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/vms body — VM (CSP 인스턴스) 생성 요청. cluster 등록과 분리된 단일 책임.
 *
 * <p>K8s cluster 의 registered/imported 경로는 별도 {@code POST /v1/clusters} 에서 다룬다.
 * 본 요청은 Pulumi 통한 VM 인프라 provision 만 트리거.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "VM 생성 요청 (Pulumi 통한 CSP 인스턴스 + Kubernetes 자동 설치)")
public class VmCreateRequest {

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.K8S_NAME_PATTERN,
            message = "vmGroupName must be RFC 1123 label (a-z, 0-9, -)")
    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
    @Schema(
            description = "VM 그룹 이름 — master + worker 인스턴스 집합을 묶는 식별자. K8s cluster registration 시에는 "
                    + "동일 이름이 cluster.id 로도 사용됨 (현재 1:1 매핑).",
            example = "demo-aws-01")
    private String vmGroupName;

    @NotBlank
    @Schema(description = "CSP — aws | gcp | azure | openstack | alibaba | oci | digitalocean", example = "aws")
    private String provider;

    @NotBlank
    @Schema(description = "CSP 리전", example = "ap-northeast-2")
    private String region;

    @Schema(description = "환경 태그 — dev | stage | prod (선택)", example = "dev")
    private String environment;

    @NotBlank
    @Schema(description = "사전 등록된 CSP credential id", example = "cred-aws-001")
    private String credentialId;

    @Schema(description = "설명 (선택)")
    private String description;

    @Schema(
            description = "Pulumi config — workerCount, instanceType 등 CSP-별 key/value",
            example = "{\"workerCount\": \"3\", \"instanceType\": \"t3.medium\"}")
    private Map<String, String> config;

    @Schema(description = "GPU 노드 포함 여부 — cluster-observability 가 dcgm-exporter 설치 결정에 활용", example = "false")
    private Boolean hasGpuNodes;
}
