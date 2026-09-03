package com.aipaas.anycloud.domain.kube;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "쿠버네티스 연결 테스트 응답 DTO")
public class KubeConnectionTestResponse {

    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Schema(description = "연결 성공 여부", example = "true")
    private boolean connected;
}
