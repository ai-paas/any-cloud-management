package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PATCH /v1/clusters/{name} body. 클러스터의 state 변경 (현재는 scale 만 지원).
 *
 * <pre>
 * # OK — 워커 수 조절
 * {"spec": {"workerCount": 5}}
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cluster state 변경 (scale 등)")
public class PatchClusterRequest {

    @Valid
    @Schema(description = "변경할 spec 필드들")
    private Spec spec;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "변경 가능한 클러스터 spec")
    public static class Spec {

        @Min(1)
        @Max(50)
        @Schema(description = "워커 노드 수 (1..50). VM 클러스터만 적용 가능.", example = "5")
        private Integer workerCount;

        // Note: hasGpuNodes 변경은 sync 동작이므로 본 비동기 PATCH 가 아닌 별도 endpoint
        // (PATCH /v1/clusters/{c}/capabilities) 로 처리. {@link PatchClusterCapabilitiesRequest}.
    }
}
