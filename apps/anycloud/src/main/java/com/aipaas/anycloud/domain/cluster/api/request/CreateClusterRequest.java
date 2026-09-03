package com.aipaas.anycloud.domain.cluster.api.request;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/clusters body. {@code source} discriminator 로 VM 신규 생성 / 외부 클러스터 등록 분기.
 *
 * <pre>
 * # VM 신규 생성 — Pulumi provision
 * {
 *   "source": "vm",
 *   "clusterName": "demo-aws-01",
 *   "spec": {
 *     "provider": "aws",
 *     "region": "ap-northeast-2",
 *     "environment": "dev",
 *     "credentialId": "cred-001",
 *     "config": { "workerCount": "3", ... }
 *   }
 * }
 *
 * # 외부 클러스터 등록 — kubeconfig 직접
 * {
 *   "source": "registered",
 *   "clusterName": "imported-aws-01",
 *   "spec": {
 *     "provider": "aws",
 *     "clusterType": "EKS",
 *     "kubeconfig": "apiVersion: ...",
 *     "description": "..."
 *   }
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cluster 생성 요청 (VM provision 또는 외부 등록)")
public class CreateClusterRequest {

    @NotNull
    @Schema(
            description = "클러스터 source — vm: Pulumi provision, registered: 외부 클러스터 등록",
            allowableValues = {"vm", "registered"},
            example = "vm")
    private Source source;

    @NotBlank
    @Pattern(
            regexp = ApiValidationConstants.K8S_NAME_PATTERN,
            message = "clusterName must be RFC 1123 label (a-z, 0-9, -)")
    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @NotNull
    @Schema(description = "source 별 spec")
    private Map<String, Object> spec;

    public enum Source {
        vm,
        registered
    }
}
