package com.aipaas.anycloud.domain.kube;

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
@Schema(description = "쿠버네티스 단일 리소스 응답 DTO")
public class KubeResourceResponse {

    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Schema(description = "네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "리소스 유형", example = "pods")
    private String resourceType;

    @Schema(description = "리소스 이름", example = "nginx-7d6f4f9d8f-abcde")
    private String resourceName;

    @Schema(description = "리소스 JSON")
    private JsonNode resource;
}
