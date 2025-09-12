package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * <pre>
 * ClassName : ChartDeployResponseDto
 * Type : class
 * Description : Helm 차트 배포 응답을 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 배포 응답 DTO")
public class ChartDeployResponseDto {

    @Schema(description = "배포 성공 여부", example = "true")
    private Boolean success;

    @Schema(description = "배포 메시지", example = "Release my-nginx has been deployed successfully")
    private String message;
}
