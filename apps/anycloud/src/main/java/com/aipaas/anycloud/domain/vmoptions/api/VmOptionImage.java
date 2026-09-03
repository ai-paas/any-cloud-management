package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "VM OS 이미지 옵션 정보")
public class VmOptionImage {

    @Schema(description = "클라우드 제공자", example = "AWS")
    String provider;

    @Schema(description = "리전", example = "ap-northeast-2")
    String region;

    @Schema(description = "이미지 식별자", example = "ami-0123456789abcdef0")
    String id;

    @Schema(description = "이미지 이름", example = "ubuntu-24.04")
    String name;

    @Schema(description = "운영체제 유형", example = "linux")
    String osType;

    @Schema(description = "운영체제 버전", example = "24.04")
    String osVersion;

    @Schema(description = "아키텍처", example = "x86_64")
    String architecture;

    @Schema(description = "이미지 owner", example = "canonical")
    String owner;

    @Schema(description = "공개 범위", example = "public")
    String visibility;

    @Schema(description = "생성 시각", example = "2026-03-31T10:15:30Z")
    String createdAt;

    @Schema(description = "추천 여부", example = "true")
    Boolean recommended;

    @Schema(description = "추천 사유", example = "Verified for kubeadm bootstrap on Ubuntu")
    String recommendationReason;
}
