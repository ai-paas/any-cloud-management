package com.aipaas.anycloud.domain.chart.api.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 릴리즈 리소스 목록 응답 DTO")
public class ChartReleaseResourcesResponse {

    @Schema(description = "클러스터 이름", example = "imported-cluster-001")
    private String clusterName;

    @Schema(description = "네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "릴리즈 이름", example = "nginx-test-release")
    private String releaseName;

    @Schema(description = "릴리즈 리소스 목록 JSON")
    private JsonNode resources;
}
