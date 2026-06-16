package com.aipaas.anycloud.domain.provisioning.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * VM 클러스터 worker 수 조절 요청. Day-2 §1 (scale up/down).
 * <p>
 * scale-down 시 운영자가 사전에 {@code kubectl drain} 으로 노드를 비우는 것을 권장한다.
 * 본 API 는 Pulumi config workerCount 갱신 + {@code pulumi up} 만 트리거하며
 * drain 자동화는 별건 (day-2-operations.md §1 후속 #2).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScaleVmClusterRequest {

    @NotNull
    @Min(1)
    @Max(50)
    private Integer workerCount;
}
