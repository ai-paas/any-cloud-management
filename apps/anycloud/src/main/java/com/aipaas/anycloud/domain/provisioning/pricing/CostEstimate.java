package com.aipaas.anycloud.domain.provisioning.pricing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * VM cluster 의 예상 비용 요약.
 *
 * <p>모든 금액은 {@link #currency} 단위 (현재 USD only). hourly = on-demand list price 기준, spot
 * 적용 시 자동 할인. 본 estimate 는 정확도 ±10-30% — 사용자에게 명시 (accuracyNote).
 *
 * <p>missing entries (catalog 에 없는 instance type) 는 status=PARTIAL — 부분 합산 + 경고.
 * 미지원 provider 는 status=UNKNOWN — 모든 금액 null.
 */
@Schema(description = "VM 클러스터 예상 비용 (정확도 ±10-30%)")
public record CostEstimate(
        @Schema(description = "예상 정확도 — FULL=모든 항목 매핑됨, PARTIAL=일부 누락, UNKNOWN=지원 안함", example = "FULL") Status status,
        @Schema(description = "통화", example = "USD") String currency,
        @Schema(description = "가격 기준 region", example = "us-east-1") String pricingRegion,
        @Schema(description = "Master 노드 합계 USD/hour", example = "0.1664") BigDecimal masterHourly,
        @Schema(description = "Worker 노드 합계 USD/hour", example = "0.3328") BigDecimal workerHourly,
        @Schema(description = "전체 cluster USD/hour (master + worker, spot 적용 후)", example = "0.4992")
                BigDecimal totalHourly,
        @Schema(description = "30일 환산 USD (= totalHourly × 24 × 30)", example = "359.42") BigDecimal monthlyProjection,
        @Schema(description = "spot/preemptible 적용 여부", example = "false") boolean spotApplied,
        @Schema(description = "spot 적용 시 절감액 — 0 if !spotApplied", example = "0.6500") BigDecimal spotDiscountFactor,
        @Schema(description = "노드 별 breakdown (master/worker 라인 별 가격)") List<LineItem> breakdown,
        @Schema(
                        description = "추정 정확도 / 한계 안내",
                        example = "Prices from us-east-1 list, ±10-30% per region/spot variance.")
                String accuracyNote) {

    /** 단일 라인 (master 또는 worker 한 row). */
    public record LineItem(
            @Schema(description = "라인 라벨", example = "master") String label,
            @Schema(description = "instance type", example = "t3.large") String instanceType,
            @Schema(description = "개수", example = "1") int count,
            @Schema(description = "단가 USD/hour", example = "0.0832") BigDecimal unitHourly,
            @Schema(description = "소계 USD/hour", example = "0.0832") BigDecimal subtotalHourly) {}

    public enum Status {
        /** 모든 instance type 가 catalog 에 있어 fully 정확. */
        FULL,
        /** 일부 instance type 가 catalog 에 없음. breakdown 에서 unitHourly=null 로 표시. */
        PARTIAL,
        /** Provider 가 catalog 에 없음 — 전체 estimate null. */
        UNKNOWN
    }
}
