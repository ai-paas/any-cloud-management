package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/clusters/{name}/operations body.
 * scale/upgrade 는 PATCH 로 표현하므로 여기 type 에 없음 — 본 endpoint 는 액션성 task 만.
 *
 * <pre>
 * { "type": "retryWorkflow" }
 * { "type": "retryRegistration" }
 * { "type": "refreshStatus" }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cluster 액션 operation 생성 요청")
public class CreateClusterOperationRequest {

    @NotNull
    @Schema(
            description = "operation type",
            allowableValues = {"retryWorkflow", "retryRegistration", "refreshStatus"})
    private ClusterOperationType type;

    public enum ClusterOperationType {
        retryWorkflow,
        retryRegistration,
        refreshStatus,
    }
}
