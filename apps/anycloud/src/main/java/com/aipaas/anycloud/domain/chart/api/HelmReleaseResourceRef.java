package com.aipaas.anycloud.domain.chart.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** Helm release 의 K8s 자원 enumerate 응답의 단일 ref. */
@Builder
@Schema(description = "Helm release 가 만든 K8s 자원의 식별 ref")
public record HelmReleaseResourceRef(
        @Schema(description = "K8s Kind (Deployment, Service 등)", example = "Deployment") String kind,
        @Schema(description = "API version (apps/v1, v1 등)", example = "apps/v1") String apiVersion,
        @Schema(description = "Namespace. cluster-scoped 자원 (PV 등) 은 빈 문자열.", example = "monitoring") String namespace,
        @Schema(description = "자원 이름", example = "nginx-controller") String name) {}
