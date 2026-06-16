package com.aipaas.anycloud.domain.cluster.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Fleet-wide agent health 응답. 등록된 cluster 전체의 agent 활성도 요약 + per-cluster 상세.
 *
 * <p>운영 대시보드 / 알람 화면에 한 번에 띄울 수 있도록 집계(total/healthy/unhealthy/noAgent)
 * 와 per-status 분포(byStatus) 를 함께 제공. {@code clusters} 는 단일 endpoint 와 동일 DTO
 * 라 UI 가 화면 전환 없이 그대로 활용 가능.
 *
 * <p>NOTE: Prometheus 시계열은 {@code AgentHealthMetricsBinder} 가 별도로 노출. 본 DTO 는
 * 인스턴스/snapshot 응답이고, 알람/SLO 는 metric 으로.
 */
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
