package com.aipaas.anycloud.domain.cluster.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/** Fleet-wide agent health 응답. 등록된 cluster 전체의 agent 활성도 요약 + per-cluster 상세. */
@Builder
@Schema(description = "Fleet-wide cluster agent health 종합 응답")
public record FleetAgentHealthResponse(
        @Schema(description = "anycloud 가 알고 있는 cluster 수", example = "12") int total,
        @Schema(description = "healthy=true 인 cluster 수", example = "10") int healthy,
        @Schema(description = "healthy=false 이며 agent 가 있는 cluster 수 (degraded/stale)", example = "1") int unhealthy,
        @Schema(description = "agent 등록이 아직 안 된 cluster 수", example = "1") int noAgent,
        @Schema(
                        description = "agent status 별 cluster 개수 (ACTIVE/REGISTERED/DEGRADED/FAILED/REVOKED/NONE)",
                        example = "{\"ACTIVE\":10,\"DEGRADED\":1,\"NONE\":1}")
                Map<String, Long> byStatus,
        @Schema(description = "per-cluster health 상세 — 단일 endpoint 와 동일 DTO") List<ClusterHealthResponse> clusters) {}
