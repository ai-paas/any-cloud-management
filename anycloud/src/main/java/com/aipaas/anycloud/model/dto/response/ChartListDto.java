package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

/**
 * <pre>
 * ClassName : ChartListDto
 * Type : class
 * Description : Helm repository의 차트 목록을 반환하기 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 목록 응답 DTO")
public class ChartListDto {

    @Schema(description = "Repository 이름", example = "bitnami")
    private String repositoryName;

    @Schema(description = "차트 목록")
    private List<ChartInfo> charts;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "차트 정보")
    public static class ChartInfo {
        @Schema(description = "차트 이름", example = "nginx")
        private String name;

        @Schema(description = "차트 버전", example = "15.4.4")
        private String version;

        @Schema(description = "차트 설명", example = "NGINX Open Source is a web server that can be also used as a reverse proxy")
        private String description;

        @Schema(description = "앱 버전", example = "1.25.3")
        private String appVersion;

        @Schema(description = "차트 키워드", example = "[\"web\", \"nginx\"]")
        private String[] keywords;

        @Schema(description = "차트 생성일", example = "2023-10-20T10:15:30Z")
        private String created;
    }
}
