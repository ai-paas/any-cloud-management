package com.aipaas.anycloud.domain.cluster.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZonedDateTime;
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
@Schema(description = "등록형 클러스터 응답 DTO")
public class ClusterResponse {

    @Schema(description = "클러스터 이름", example = "imported-cluster-001")
    private String clusterName;

    @Schema(description = "클러스터 설명", example = "운영용 Kubernetes 클러스터")
    private String description;

    @Schema(description = "클러스터 상태", example = "READY")
    private String status;

    @Schema(description = "쿠버네티스 버전", example = "v1.31.2")
    private String version;

    // apiServerUrl / apiServerIp / monitServerUrl 응답 필드 제거
    // cluster-agent 가 모든 K8s API / monitoring 호출 대행. cluster 자체에 backend 가
    // 직접 dial 하지 않음.

    @Schema(description = "클러스터 유형", example = "ONPREM")
    private String clusterType;

    @Schema(description = "클러스터 제공자", example = "OPENSTACK")
    private String clusterProvider;

    @Schema(description = "프로비저닝 유형", example = "IMPORTED")
    private String provisioningType;

    @Schema(description = "프로비저닝 상태", example = "READY")
    private String provisioningStatus;

    @Schema(description = "Pulumi stack 이름", example = "vm-demo-aws-01")
    private String stackName;

    @Schema(description = "생성 시각")
    private ZonedDateTime createdAt;

    @Schema(description = "수정 시각")
    private ZonedDateTime updatedAt;
}
