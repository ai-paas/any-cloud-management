package com.aipaas.anycloud.domain.provisioning.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** VM 클러스터 worker 수 조절 요청. Day-2 §1 (scale up/down). */
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
