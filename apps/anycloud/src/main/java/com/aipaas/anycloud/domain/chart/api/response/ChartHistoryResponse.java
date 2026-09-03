package com.aipaas.anycloud.domain.chart.api.response;

import com.aipaas.anycloud.domain.chart.api.ChartHistoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartHistoryResponse {

    @Schema(description = "릴리즈 이름", example = "ingress")
    private String releaseName;

    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Schema(description = "네임스페이스", example = "ingress-nginx")
    private String namespace;

    @Schema(description = "revision 목록 (가장 최근이 마지막)")
    private List<ChartHistoryItem> revisions;
}
