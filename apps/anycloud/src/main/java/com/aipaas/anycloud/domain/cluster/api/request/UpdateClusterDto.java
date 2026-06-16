package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 클러스터 연동(수정) 요청 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateClusterDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -2290543268215032683L;

    @Schema(description = "클러스터 설명")
    @Size(max = 500, message = "설명은 500자를 초과할 수 없습니다")
    private String description;

    @Schema(description = "클러스터 유형 (Public, Private)")
    // @Pattern(regexp = "^(Public|Private)$", message = "클러스터 유형은 Public 또는
    // Private이어야 합니다")
    private String clusterType;

    @Schema(description = "클러스터 공급자 (AWS, GCP, Azure, OpenStack 등)")
    @Size(max = 50, message = "클러스터 공급자명은 50자를 초과할 수 없습니다")
    private String clusterProvider;

    // apiServerUrl/IP, server/client CA, client key/token, monitServerURL
    // 모두 update 대상에서 제거 — cluster-agent 가 모든 K8s 작업 대행.
}
