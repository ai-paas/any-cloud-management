package com.aipaas.anycloud.domain.cluster.api.request;

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
 * POST /v1/clusters/{cluster}/helm-releases body (application/json).
 * <p>
 * values 입력은 다음 중 <b>최대 하나</b> 만 제공:
 * <ul>
 *   <li>{@code values} — JSON object (권장). 백엔드가 YAML 로 직렬화하여 helm 에 전달.</li>
 *   <li>{@code valuesYaml} — values.yaml 문자열 (짧은 케이스). 들여쓰기 escape 부담.</li>
 * </ul>
 * 큰 values 파일은 multipart/form-data 변형 endpoint 사용 권장.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm release 설치 요청 (JSON)")
public class InstallHelmReleaseRequest {

    @NotBlank
    @Schema(description = "릴리즈 이름 (K8s name 규칙)", example = "ingress")
    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
    private String releaseName;

    @NotBlank
    @Schema(description = "chart 참조 (\"<repo>/<chart>\")", example = "bitnami/nginx")
    @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private String chart;

    @Schema(description = "chart version (선택, null=latest)", example = "15.3.0")
    @Pattern(regexp = "^[A-Za-z0-9._+-]{1,32}$")
    private String version;

    @Schema(description = "namespace (선택, default)", example = "web")
    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
    private String namespace;

    @Schema(
            description = "values 객체 — JSON 으로 자연스럽게 작성. 백엔드가 YAML 로 직렬화.",
            example = "{\"replicaCount\":3,\"image\":{\"repository\":\"nginx\"}}")
    private Map<String, Object> values;

    @Schema(description = "values.yaml 내용 (raw string). 짧은 values 용. values 와 동시 지정 불가.")
    @Size(max = 1_000_000)
    private String valuesYaml;
}
