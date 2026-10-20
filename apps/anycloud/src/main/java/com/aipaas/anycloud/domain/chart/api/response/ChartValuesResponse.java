package com.aipaas.anycloud.domain.chart.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/** Helm 차트 values.yaml 응답 DTO. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 values.yaml 응답 DTO")
public class ChartValuesResponse {

    @Schema(description = "Repository 이름", example = "bitnami")
    private String repositoryName;

    @Schema(description = "차트 이름", example = "nginx")
    private String chartName;

    @Schema(description = "차트 버전", example = "15.4.4")
    private String version;

    @Schema(
            description = "values.yaml 내용",
            example = "# Default values for nginx\nreplicaCount: 1\nimage:\n  repository: nginx\n  tag: 1.25.3")
    private String valuesContent;
}
