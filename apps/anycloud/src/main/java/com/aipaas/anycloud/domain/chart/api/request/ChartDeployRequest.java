package com.aipaas.anycloud.domain.chart.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;

/** Helm 차트 배포 요청 DTO. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Helm 차트 배포 요청 DTO")
public class ChartDeployRequest {

    @NotBlank(message = "릴리즈 이름은 필수입니다")
    @Schema(description = "Helm 릴리즈 이름", example = "my-nginx")
    private String releaseName;

    @NotBlank(message = "클러스터 이름은 필수입니다")
    @Schema(description = "배포할 클러스터 이름", example = "imported-cluster-001")
    private String clusterName;

    @Schema(description = "배포할 네임스페이스", example = "default")
    private String namespace;

    @Schema(description = "차트 버전 (미지정시 최신 버전)", example = "15.4.4")
    private String version;

    @Schema(description = "values.yaml 파일 (선택사항)", example = "values.yaml")
    private MultipartFile valuesFile;
}
