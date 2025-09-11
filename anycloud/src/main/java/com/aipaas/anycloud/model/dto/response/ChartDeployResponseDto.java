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

    @Schema(description = "릴리즈 이름", example = "my-nginx")
    private String releaseName;

    @Schema(description = "네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "배포된 클러스터 ID", example = "cluster-001")
    private String clusterId;

    @Schema(description = "배포된 차트 버전", example = "15.4.4")
    private String chartVersion;

    @Schema(description = "배포 상태", example = "deployed")
    private String status;

    @Schema(description = "배포 메시지", example = "Release my-nginx has been deployed successfully")
    private String message;

    @Schema(description = "배포 결과 상세 정보", example = "NAME: my-nginx\nLAST DEPLOYED: 2023-10-20 10:15:30\nNAMESPACE: default\nSTATUS: deployed")
    private String output;
}
