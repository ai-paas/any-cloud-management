package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/clusters/{c}/helm-releases/{r}/operations body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm release 액션 operation")
public class HelmReleaseOperationRequest {

    @NotNull
    @Schema(
            description = "operation type",
            allowableValues = {"rollback"})
    private Type type;

    @Min(0)
    @Schema(description = "rollback 대상 revision (0 = 직전 성공)", example = "2")
    private Integer revision;

    @Schema(description = "리소스 ready 까지 wait", example = "false")
    private Boolean wait;

    public enum Type {
        rollback
    }
}
