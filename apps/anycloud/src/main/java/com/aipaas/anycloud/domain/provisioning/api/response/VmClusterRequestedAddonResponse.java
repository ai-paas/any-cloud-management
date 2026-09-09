package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 프로비저닝 요청이 함축해 자동 등록된 addon 의 설치 상태. 운영자가 나중에 추가한 addon 은 제외. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "요청 addon 설치 상태")
public class VmClusterRequestedAddonResponse {

    @Schema(description = "addon 카탈로그 id", example = "nvidia-gpu-operator")
    private String catalogId;

    @Schema(description = "READY 판정 반영 여부", example = "REQUIRED")
    private String requirement;

    @Schema(description = "설치 상태 판정", example = "NOT_READY")
    private String health;

    @Schema(description = "미충족 사유", example = "helm install timed out")
    private String detail;
}
