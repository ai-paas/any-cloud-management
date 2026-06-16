package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 클러스터 연동(추가) 요청 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateClusterDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -2290543268215032683L;

    @NotBlank
    @Schema(description = "클러스터 유형")
    private String clusterType;

    @NotBlank
    @Schema(description = "클러스터 공급자")
    private String clusterProvider;

    @NotBlank
    @Schema(description = "클러스터 아이디(클러스터 명)")
    private String clusterName;

    @Schema(description = "클러스터 설명")
    private String description;

    // apiServerIp / apiServerUrl / serverCA / clientCA / clientKey /
    // clientToken / monitServerURL 모두 제거. Cluster 등록은 metadata 만 받고, cluster-agent
    // 가 in-cluster 에서 모든 K8s API + monitoring 호출 대행. 사용자는 응답에 박힌
    // bootstrap.helmInstallCommand 로 cluster-agent 를 자기 kubectl context 에서 install.

    /** GPU 노드 포함 여부. null → false. */
    @Schema(
            description = "GPU 노드 포함 (cluster-observability auto-installer 가 dcgm-exporter 추가 설치)",
            example = "false",
            defaultValue = "false")
    private Boolean hasGpuNodes;
}
