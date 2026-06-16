package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.AddonCatalog;
import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Catalog default + caller override merge.
 *
 * <p>{@link AddonSpec} (frontend) → {@link ClusterAddonEntity} (DB) 변환 시 catalog (Option B)
 * 의 default 값을 base 로, caller 의 non-null/non-blank override 만 적용.
 *
 * <p>두 모드:
 * <ul>
 *   <li>catalog 기반: {@code spec.catalogId} 가 catalog 에 존재 — chart 모든 필드 catalog 에서 채움.</li>
 *   <li>custom: catalogId null — caller 가 chart 필드 모두 명시 (chartRepo/chartName/chartVersion 필수).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AddonSpecResolver {

    private final AddonCatalog catalog;

    /**
     * Spec → Entity. validation 포함 — required field 누락 시 IllegalArgumentException.
     *
     * @param spec      frontend 가 보낸 spec.
     * @param clusterId 대상 cluster id.
     * @return DB 저장 직전 (id/state/timestamps 는 @PrePersist 가 set).
     */
    public ClusterAddonEntity resolve(AddonSpec spec, String clusterId) {
        if (spec == null) {
            throw new IllegalArgumentException("AddonSpec is required");
        }
        if (clusterId == null || clusterId.isBlank()) {
            throw new IllegalArgumentException("clusterId is required");
        }
        // catalogId 명시했지만 catalog 에 없으면 fail-fast.
        // silently custom mode 로 fallback 하면 chart 필드 missing 으로 모호한 에러 — 명시적 reject.
        if (spec.catalogId() != null
                && !spec.catalogId().isBlank()
                && catalog.find(spec.catalogId()).isEmpty()) {
            throw new IllegalArgumentException(
                    "unknown catalogId: " + spec.catalogId() + " (GET /v1/addons 로 사용 가능한 id 목록 확인)");
        }
        Optional<AddonCatalogProperties.Entry> entry = catalog.find(spec.catalogId());
        AddonType type = spec.type() != null
                ? spec.type()
                : entry.map(AddonCatalogProperties.Entry::type).orElse(null);
        if (type == null) {
            throw new IllegalArgumentException("addon type is required (spec.type or catalog)");
        }
        String chartRepo = firstNonBlank(
                spec.chartRepo(),
                entry.map(AddonCatalogProperties.Entry::chartRepo).orElse(null));
        String chartName = firstNonBlank(
                spec.chartName(),
                entry.map(AddonCatalogProperties.Entry::chartName).orElse(null));
        String chartVersion = firstNonBlank(
                spec.chartVersion(),
                entry.map(AddonCatalogProperties.Entry::chartVersion).orElse(null));
        if (isBlank(chartRepo) || isBlank(chartName) || isBlank(chartVersion)) {
            throw new IllegalArgumentException(
                    "chartRepo/chartName/chartVersion required (catalog default 없음 + spec 미명시)");
        }
        String namespace = firstNonBlank(
                spec.namespace(),
                entry.map(AddonCatalogProperties.Entry::namespace).orElse(null));
        String releaseName = firstNonBlank(
                spec.releaseName(),
                entry.map(AddonCatalogProperties.Entry::releaseName).orElse(chartName));
        String repoUrl = firstNonBlank(
                spec.repoUrl(), entry.map(AddonCatalogProperties.Entry::repoUrl).orElse(null));
        String valuesYaml = firstNonBlank(
                spec.valuesYaml(),
                entry.map(AddonCatalogProperties.Entry::defaultValuesYaml).orElse(null));

        return ClusterAddonEntity.builder()
                .clusterId(clusterId)
                .addonType(type)
                .catalogId(spec.catalogId())
                .releaseName(releaseName)
                .namespace(namespace == null ? "default" : namespace)
                .chartRepo(chartRepo)
                .chartName(chartName)
                .chartVersion(chartVersion)
                .repoUrl(repoUrl)
                .valuesYaml(valuesYaml)
                .enabled(spec.enabled() == null ? Boolean.TRUE : spec.enabled())
                .build();
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        return b;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
