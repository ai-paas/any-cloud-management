package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /v1/clusters/{cluster}/helm-releases/{releaseName} body — -3.
 *
 * <p>InstallHelmReleaseRequest 와 거의 동일 — release name 은 URL path, atomic/reuseValues/
 * resetValues 만 upgrade 특화.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm release 업그레이드 요청")
public class UpgradeHelmReleaseRequest {

    @NotBlank
    @Schema(description = "차트 ref (repo/chart 형식)", example = "bitnami/nginx")
    private String chart;

    @NotBlank
    @Schema(description = "차트 버전 (반드시 명시 — latest 는 추적성/예측가능성 측면에서 거부)", example = "15.4.0")
    private String version;

    @Schema(description = "네임스페이스 (생략 시 default)", example = "web")
    private String namespace;

    @Schema(description = "values (JSON object — backend 가 YAML 로 직렬화). reuseValues=false 일 때 적용.")
    private Map<String, Object> values;

    @Schema(description = "values.yaml 문자열 (대안). values + valuesYaml 동시 지정 시 400.")
    private String valuesYaml;

    @Schema(
            description = "실패 시 자동 rollback (helm CLI --atomic). production 권장 (default true).",
            example = "true",
            defaultValue = "true")
    private Boolean atomic;

    @Schema(
            description = "기존 release 의 values 보존 + 새 values merge (helm CLI --reuse-values). " + "버전만 올릴 때 유용.",
            example = "false",
            defaultValue = "false")
    private Boolean reuseValues;

    @Schema(
            description = "기존 values 모두 reset, chart default + 새 values 만 사용 "
                    + "(helm CLI --reset-values). reuseValues 와 동시 true 면 resetValues 우선.",
            example = "false",
            defaultValue = "false")
    private Boolean resetValues;
}
