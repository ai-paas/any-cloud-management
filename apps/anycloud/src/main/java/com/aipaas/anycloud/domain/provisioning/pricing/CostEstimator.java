package com.aipaas.anycloud.domain.provisioning.pricing;

import com.aipaas.anycloud.domain.cluster.model.VmClusterSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * VmClusterSpec → {@link CostEstimate}.
 *
 * <p>읽는 keys (spec.config map):
 * <ul>
 *   <li>{@code masterInstanceType} (required)</li>
 *   <li>{@code workerInstanceType} (required — master 와 같을 수 있음)</li>
 *   <li>{@code masterCount} — 미지정 시 1</li>
 *   <li>{@code workerCount} — 미지정 시 0</li>
 * </ul>
 *
 * <p>계산 식:
 * <pre>
 * raw_hourly  = master_price * masterCount + worker_price * workerCount
 * spot_factor = useSpot ? catalog.spotDiscount(provider) : 0
 * total       = raw_hourly * (1 - spot_factor)
 * monthly     = total * 24 * 30
 * </pre>
 *
 * <p>spot 은 master 도 똑같이 적용 (catalog 표준은 cluster-wide flag). 운영에서는 master 만 따로 제외
 * 하는 advanced policy 가능 — 별 sprint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostEstimator {

    private static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(24 * 30);
    private static final int SCALE_HOURLY = 4;
    private static final int SCALE_MONTHLY = 2;

    private final PriceCatalog catalog;

    public CostEstimate estimate(VmClusterSpec spec) {
        if (spec == null) {
            return unknown("anycloud", "spec was null");
        }
        return estimate(spec.provider(), spec.config(), Boolean.TRUE.equals(spec.useSpot()));
    }

    /**
     * VmClusterSpec 없이 raw 입력에서 추정 — preflight 가 ProvisionClusterRequest 에서 직접 호출.
     *
     * @param provider   "aws" / "gcp" / "azure" (대소문자 무관)
     * @param config     master/worker instance type/count 가 포함된 map
     * @param useSpot    spot/preemptible 적용 여부
     */
    public CostEstimate estimate(String providerRaw, Map<String, String> configRaw, boolean useSpot) {
        String provider = providerRaw == null ? "" : providerRaw.toLowerCase(Locale.ROOT);
        Optional<PriceCatalog.ProviderMetadata> metaOpt = catalog.metadata(provider);
        if (metaOpt.isEmpty()) {
            return unknown(
                    provider,
                    "Provider " + provider + " not present in pricing catalog. " + "Supported: "
                            + catalog.supportedProviders());
        }

        PriceCatalog.ProviderMetadata meta = metaOpt.get();
        Map<String, String> cfg = configRaw == null ? Collections.emptyMap() : configRaw;
        // Pulumi config map 은 `anycloud-k8s:*` prefix 사용 (Pulumi config namespacing).
        // caller (VmClusterPreflight 등) 가 prefix 채워서 보낼 수도, 안 채울 수도 있어 양쪽 lookup.
        String masterType = lookup(cfg, "masterInstanceType");
        String workerType = lookup(cfg, "workerInstanceType");
        int masterCount = intOr(lookup(cfg, "masterCount"), 1);
        int workerCount = intOr(lookup(cfg, "workerCount"), 0);

        BigDecimal spotFactor = useSpot ? catalog.spotDiscount(provider) : BigDecimal.ZERO;

        List<CostEstimate.LineItem> breakdown = new ArrayList<>();
        boolean anyMissing = false;

        LineCalc m = calcLine("master", provider, masterType, masterCount);
        breakdown.add(m.item);
        anyMissing |= m.missing;

        LineCalc w = calcLine("worker", provider, workerType, workerCount);
        breakdown.add(w.item);
        anyMissing |= w.missing;

        BigDecimal rawHourly = nz(m.subtotal).add(nz(w.subtotal));
        BigDecimal multiplier = BigDecimal.ONE.subtract(spotFactor);
        BigDecimal totalHourly = rawHourly.multiply(multiplier).setScale(SCALE_HOURLY, RoundingMode.HALF_UP);
        BigDecimal monthly = totalHourly.multiply(HOURS_PER_MONTH).setScale(SCALE_MONTHLY, RoundingMode.HALF_UP);

        String note = buildAccuracyNote(meta, useSpot, anyMissing);
        CostEstimate.Status status = anyMissing ? CostEstimate.Status.PARTIAL : CostEstimate.Status.FULL;

        return new CostEstimate(
                status,
                meta.currency(),
                meta.region(),
                roundHourly(nz(m.subtotal).multiply(multiplier)),
                roundHourly(nz(w.subtotal).multiply(multiplier)),
                totalHourly,
                monthly,
                useSpot,
                spotFactor,
                breakdown,
                note);
    }

    // ----- internal -----

    private LineCalc calcLine(String label, String provider, String type, int count) {
        if (type == null || type.isBlank() || count <= 0) {
            return new LineCalc(new CostEstimate.LineItem(label, type, count, null, null), BigDecimal.ZERO, false);
        }
        Optional<InstancePrice> price = catalog.lookup(provider, type);
        if (price.isEmpty()) {
            return new LineCalc(new CostEstimate.LineItem(label, type, count, null, null), BigDecimal.ZERO, true);
        }
        BigDecimal unit = price.get().hourly();
        BigDecimal subtotal = unit.multiply(BigDecimal.valueOf(count));
        return new LineCalc(
                new CostEstimate.LineItem(label, type, count, unit, roundHourly(subtotal)), subtotal, false);
    }

    private static String buildAccuracyNote(PriceCatalog.ProviderMetadata meta, boolean useSpot, boolean missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("Prices from ")
                .append(meta.region())
                .append(" list (")
                .append(meta.currency())
                .append(", catalog dated ")
                .append(meta.updatedAt())
                .append("). ");
        sb.append("Other regions vary ±5-30%. ");
        if (useSpot) {
            sb.append("Spot/preemptible discount is an industry average — actual price can fluctuate hourly. ");
        }
        if (missing) {
            sb.append("Some instance types are NOT in the catalog — only the priced lines are summed. ");
        }
        sb.append("Storage / network egress / load balancer NOT included.");
        return sb.toString();
    }

    private static CostEstimate unknown(String provider, String reason) {
        return new CostEstimate(
                CostEstimate.Status.UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                BigDecimal.ZERO,
                List.of(),
                reason);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static BigDecimal roundHourly(BigDecimal b) {
        return b == null ? null : b.setScale(SCALE_HOURLY, RoundingMode.HALF_UP);
    }

    private static int intOr(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Pulumi config map 은 namespace 접두사 `anycloud-k8s:` 를 사용 — 그러나 caller (또는 향후 다른
     * source) 가 plain key 로 넣을 수도 있어 양쪽 lookup. plain key 우선 (override 의도 존중).
     */
    private static final String PULUMI_NS = "anycloud-k8s:";

    static String lookup(Map<String, String> cfg, String key) {
        String v = cfg.get(key);
        if (v != null) return v;
        return cfg.get(PULUMI_NS + key);
    }

    /** 한 line 의 계산 결과. */
    private record LineCalc(CostEstimate.LineItem item, BigDecimal subtotal, boolean missing) {}
}
