package com.aipaas.anycloud.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * <pre>
 * ClassName : ChartDetailDto
 * Type : class
 * Description : Helm repository의 특정 차트 상세 정보를 반환하기 위한 DTO입니다.
 * Related : ChartController, ChartService
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 상세 응답 DTO (최신버전 기준 + 버전 히스토리)")
public class ChartDetailDto {

    @Schema(description = "Repository 이름", example = "bitnami")
    private String repositoryName;

    @Schema(description = "차트 이름", example = "nginx")
    private String name;

    @Schema(description = "최신 차트 버전", example = "15.4.4")
    private String version;

    @Schema(description = "앱 버전", example = "1.25.3")
    private String appVersion;

    @Schema(description = "차트 설명", example = "NGINX Open Source is a web server...")
    private String description;

    @Schema(description = "차트 생성일", example = "2023-10-20T10:15:30Z")
    private String created;

    @Schema(description = "차트 유지보수자 목록",   example = "[{\"name\": \"Sonatype\", \"email\": \"support@sonatype.com\"}]")
    private List<Map<String, Object>> maintainers;

    @Schema(description = "차트 키워드", example = "[\"web\", \"nginx\"]")
    private String[] keywords;

    @Schema(description = "차트 소스 URL", example = "https://github.com/bitnami/charts/tree/master/bitnami/nginx")
    private String source;

    @Schema(description = "차트 홈 URL", example = "https://nginx.org/")
    private String home;

    @Schema(description = "차트 아이콘 URL", example = "https://nginx.org/icon.png")
    private String icon;

    @Schema(description = "차트 종속성 정보")
    private List<Dependency> dependencies;

    @Schema(description = "버전 히스토리 (버전명 + 릴리즈일)")
    private List<VersionHistory> versionHistory;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "차트 종속성 정보 DTO")
    public static class Dependency {
        private String name;
        private String version;
        private String repository;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "버전 히스토리 DTO")
    public static class VersionHistory {
        @Schema(description = "차트 버전", example = "4.7.0")
        private String version;

        @Schema(description = "앱 버전", example = "1.25.3")
        private String appVersion;

        @Schema(description = "릴리즈 날짜", example = "2024-02-15")
        private String created;
    }
}
