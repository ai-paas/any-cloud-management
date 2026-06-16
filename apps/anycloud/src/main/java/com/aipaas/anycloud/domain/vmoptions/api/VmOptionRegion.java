package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "VM 옵션 리전 정보")
public class VmOptionRegion {

    @Schema(description = "클라우드 제공자", example = "AWS")
    String provider;

    @Schema(description = "리전 식별자", example = "ap-northeast-2")
    String id;

    @Schema(description = "리전 표시 이름", example = "Asia Pacific (Seoul)")
    String name;

    @Schema(description = "사용 가능 여부", example = "true")
    Boolean available;
}
