package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 운영자 수동 cluster capability override (sync).
 *
 * @param hasGpuNodes GPU 노드 포함 여부. null 이면 변경 안 함.
 */
@Schema(description = "Cluster capability flag 수동 설정 (sync, immediate effect)")
public record PatchClusterCapabilitiesRequest(
        @Schema(description = "GPU 노드 포함 여부 — true 면 dcgm-exporter 자동 설치 대상", example = "true") Boolean hasGpuNodes) {}
