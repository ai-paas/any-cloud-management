package com.aipaas.anycloud.domain.provisioning.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PATCH /v1/vms/{name} body. 현재는 scale (workerCount) 만 지원. spec subtree 가 향후 확장.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "VM 변경 요청 — 현재는 scale 만 지원")
public class VmPatchRequest {

    @Schema(description = "변경 항목들")
    private Spec spec;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "변경 가능한 VM spec subtree")
    public static class Spec {
        @Min(1)
        @Max(50)
        @Schema(description = "워커 노드 수 (1..50)", example = "5")
        private Integer workerCount;
    }
}
