package com.aipaas.anycloud.domain.agent.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin fleet 페이지의 cluster-agent 목록 응답.
 * Server-side pagination — page/size/total/totalPages 모두 backend 계산.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Admin agents fleet list")
public record AdminAgentListResponse(
        List<Item> items,
        int total,
        int page,
        int size,
        int totalPages) {

    /**
     * Fleet item — 단일 agent row. HA replica 는 agentInstanceId 로 구분.
     * podName / leader 컬럼은 entity 미보유 — v2 에서 보강 예정.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Fleet item")
    public record Item(
            @Schema(description = "agent_id (PK)") String agentId,
            @Schema(description = "cluster 이름") String clusterName,
            @Schema(description = "HA replica 식별자 (pod 이름 proxy)") String agentInstanceId,
            @Schema(description = "CONNECTED / STALE / DISCONNECTED") String status,
            @Schema(description = "agent build 버전") String agentVersion,
            @Schema(description = "마지막 heartbeat 시각") LocalDateTime lastSeenAt,
            @Schema(description = "응답 시점 기준 (초)") Long lastSeenAgeSec,
            @Schema(description = "마지막 에러 메시지") String lastError) {}
}
