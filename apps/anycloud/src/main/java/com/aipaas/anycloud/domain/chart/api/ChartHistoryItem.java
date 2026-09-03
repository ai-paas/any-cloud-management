package com.aipaas.anycloud.domain.chart.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Helm release revision 한 건. {@code helm history -o json} 의 element 와 1:1.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartHistoryItem {

    @Schema(description = "revision 번호 (1부터)", example = "3")
    private int revision;

    @Schema(description = "이 revision 이 기록된 시각 (ISO 8601)", example = "2026-05-11T03:45:21Z")
    private String updated;

    @Schema(description = "status (deployed / superseded / failed / pending-* / uninstalled)", example = "superseded")
    private String status;

    @Schema(description = "chart 식별자", example = "ingress-nginx-4.10.1")
    private String chart;

    @Schema(description = "app version", example = "1.10.1")
    private String appVersion;

    @Schema(description = "Helm 이 기록한 변경 설명", example = "Upgrade complete")
    private String description;
}
