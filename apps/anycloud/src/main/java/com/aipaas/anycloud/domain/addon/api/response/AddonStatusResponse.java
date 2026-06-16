package com.aipaas.anycloud.domain.addon.api.response;

import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZonedDateTime;

/**
 * GET /v1/clusters/{c}/addons response — frontend addon list / status polling.
 *
 * <p>HTTP response body naming 은 {@code docs/conventions/dto-naming-convention.md} §1 표준 ({@code
 * *Response}). OpenAPI schema 명은 {@code @Schema(name="AddonStatus")} 으로 고정해 wire 호환 보장.
 *
 * <p>{@link #lastOperationId} 가 set 이면 OperationEventsController SSE 로 progress 구독 가능.
 */
@Schema(name = "AddonStatus", description = "Cluster addon state snapshot")
public record AddonStatusResponse(
        String id,
        String clusterId,
        AddonType type,
        String catalogId,
        String releaseName,
        String namespace,
        String chartRepo,
        String chartName,
        String chartVersion,
        String repoUrl,
        String valuesYaml,
        AddonState state,
        String lastOperationId,
        String lastError,
        Integer attempts,
        Boolean enabled,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt,
        @Schema(
                        description = "PENDING addon 이 아직 enqueue 안 된 이유 (예: cluster 가 ACTIVE 아님). "
                                + "enqueue/설치되면 null. 정체 원인 진단용.")
                String pendingReason) {}
