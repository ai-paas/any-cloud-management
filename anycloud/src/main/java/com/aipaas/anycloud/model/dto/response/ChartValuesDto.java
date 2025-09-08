package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * <pre>
 * ClassName : ChartValuesDto
 * Type : class
 * Description : Helm 차트의 values.yaml 내용을 반환하기 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 values.yaml 응답 DTO")
public class ChartValuesDto {

    @Schema(description = "Repository 이름", example = "bitnami")
    private String repositoryName;

    @Schema(description = "차트 이름", example = "nginx")
    private String chartName;

    @Schema(description = "차트 버전", example = "15.4.4")
    private String version;

    @Schema(description = "values.yaml 내용", example = "# Default values for nginx\nreplicaCount: 1\nimage:\n  repository: nginx\n  tag: 1.25.3")
    private String valuesContent;
}
