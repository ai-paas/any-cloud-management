package com.aipaas.anycloud.domain.cluster.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * Cluster 의 종합 health 응답. Agent 가 ACTIVE 여부 + last_seen 신선도 + agent → K8s API
 * 활성도 종합 판단.
 */
@Builder
@Schema(description = "Cluster + agent health 종합 응답")
public record ClusterHealthResponse(
        @Schema(description = "Cluster 의 플랫폼 내부 ID", example = "demo-aws-01") String clusterId,
        @Schema(description = "종합 healthy 여부", example = "true") boolean healthy,
        @Schema(description = "사람이 읽을 수 있는 상태 요약", example = "stream up, heartbeat 8s ago") String summary,
        @Schema(
                        description = "Agent 상태 (REGISTERING / REGISTERED / ACTIVE / DEGRADED / FAILED / REVOKED)",
                        example = "ACTIVE")
                String agentStatus,
        @Schema(description = "Backend process 안의 agent stream 활성 여부", example = "true") boolean streamActive,
        @Schema(description = "Agent 의 마지막 heartbeat 수신 시각", example = "2026-05-12T14:30:00") LocalDateTime lastSeenAt,
        @Schema(description = "Agent 의 K8s API 마지막 성공 시각", example = "2026-05-12T14:29:55")
                LocalDateTime lastK8sApiOkAt,
        @Schema(description = "마지막 활동으로부터 지난 초", example = "8") Long lastSeenSecondsAgo) {}
