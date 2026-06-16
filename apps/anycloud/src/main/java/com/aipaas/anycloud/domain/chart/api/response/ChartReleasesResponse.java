package com.aipaas.anycloud.domain.chart.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.*;

/**
 * <pre>
 * ClassName : ChartReleasesResponse
 * Type : class
 * Description : Helm 릴리즈 목록 응답을 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 릴리즈 목록 응답 DTO")
public class ChartReleasesResponse {

    @Schema(description = "릴리즈 목록")
    private List<ReleaseInfo> releases;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "릴리즈 정보")
    public static class ReleaseInfo {

        @Schema(description = "릴리즈 이름", example = "nginx-test-release")
        private String name;

        @Schema(description = "네임스페이스", example = "default")
        private String namespace;

        @Schema(description = "차트 이름", example = "nginx")
        private String chart;

        @Schema(description = "차트 버전", example = "15.4.4")
        private String chartVersion;

        @Schema(description = "릴리즈 버전", example = "1")
        private String revision;

        @Schema(description = "상태", example = "deployed")
        private String status;

        @Schema(description = "업데이트 시간", example = "2026-04-03T15:20:00Z")
        private String updated;
    }
}
