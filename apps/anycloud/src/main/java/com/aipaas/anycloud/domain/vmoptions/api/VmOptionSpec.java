package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "VM 스펙 옵션 정보")
public class VmOptionSpec {

    @Schema(description = "클라우드 제공자", example = "AWS")
    String provider;

    @Schema(description = "리전", example = "ap-northeast-2")
    String region;

    @Schema(description = "스펙 식별자", example = "t3.large")
    String id;

    @Schema(description = "스펙 이름", example = "t3.large")
    String name;

    @Schema(description = "스펙 family", example = "t3")
    String family;

    @Schema(description = "vCPU 수", example = "2")
    Integer vcpu;

    @Schema(description = "메모리(GB)", example = "8")
    Double memoryGb;

    @Schema(description = "GPU 수", example = "0")
    Integer gpuCount;

    @Schema(description = "아키텍처", example = "x86_64")
    String architecture;

    @Schema(description = "설명", example = "Balanced general-purpose instance")
    String description;

    @Schema(description = "사용 가능 여부", example = "true")
    Boolean available;

    @Schema(description = "추천 여부", example = "true")
    Boolean recommended;

    @Schema(description = "추천 사유", example = "Good default for control-plane and worker nodes")
    String recommendationReason;
}
