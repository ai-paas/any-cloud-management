package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * <pre>
 * ClassName : ChartReadmeDto
 * Type : class
 * Description : Helm 차트의 README.md 내용을 반환하기 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 README.md 응답 DTO")
public class ChartReadmeDto {

    @Schema(description = "Repository 이름", example = "bitnami")
    private String repositoryName;

    @Schema(description = "차트 이름", example = "nginx")
    private String chartName;

    @Schema(description = "차트 버전", example = "15.4.4")
    private String version;

    @Schema(description = "README.md 내용", example = "# NGINX\n\nNGINX Open Source is a web server...")
    private String readmeContent;
}
