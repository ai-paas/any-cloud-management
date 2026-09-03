package com.aipaas.anycloud.domain.vmoptions.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "VM 옵션 Provider 정보")
public class VmOptionProvider {

    @Schema(description = "클라우드 제공자 식별자", example = "AWS")
    String provider;

    @Schema(description = "화면 표시 이름", example = "Amazon Web Services")
    String displayName;

    @Schema(description = "리전 조회 지원 여부", example = "true")
    Boolean supportsRegions;

    @Schema(description = "VM 스펙 조회 지원 여부", example = "true")
    Boolean supportsInstanceTypes;

    @Schema(description = "OS 이미지 조회 지원 여부", example = "true")
    Boolean supportsImages;

    @Schema(description = "실시간 조회 구현 여부", example = "true")
    Boolean liveDiscoveryImplemented;

    @Schema(description = "추천 리전", example = "ap-northeast-2")
    String recommendedRegion;

    @Schema(description = "추천 VM 스펙", example = "t3.large")
    String recommendedVmSpec;

    @Schema(description = "추천 OS 이미지", example = "ubuntu-24.04")
    String recommendedOsImage;

    @Schema(description = "기본 worker 수", example = "2")
    Integer defaultWorkerCount;

    @Schema(description = "기본 Kubernetes 버전", example = "1.30")
    String defaultKubernetesVersion;

    @Schema(description = "추가 메모", example = "Live discovery available")
    String notes;
}
