package com.aipaas.anycloud.domain.chart.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * ClassName : ChartStatusResponse
 * Type : class
 * Description : Helm 릴리즈 상태 조회 응답 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 릴리즈 상태 조회 응답 DTO")
public class ChartStatusResponse {

    @Schema(description = "Helm 릴리즈 이름", example = "nginx-test-release")
    private String releaseName;

    @Schema(description = "대상 클러스터 이름", example = "imported-cluster-001")
    private String clusterName;

    @Schema(description = "배포 네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "현재 릴리즈 상태", example = "deployed")
    private String status;

    @Schema(description = "상세 설명", example = "Release status retrieved successfully.")
    private String detail;
}
