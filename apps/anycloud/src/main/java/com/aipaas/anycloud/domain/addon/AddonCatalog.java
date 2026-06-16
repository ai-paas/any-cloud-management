package com.aipaas.anycloud.domain.addon;

import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Addon catalog — addons.yaml 의 memoized lookup.
 *
 * <p>{@code GET /v1/addons} 응답 source, {@code AddonSpec.catalogId} resolve,
 * installer 가 catalog default 와 caller override 를 merge 할 때 사용.
 */
@Component
@EnableConfigurationProperties(AddonCatalogProperties.class)
public class AddonCatalog {

    private final List<AddonCatalogProperties.Entry> addons;
    private final Map<String, AddonCatalogProperties.Entry> byId;

    public AddonCatalog(AddonCatalogProperties props) {
        this.addons = props == null || !props.enabled() || props.addons() == null ? List.of() : props.addons();
        this.byId = this.addons.stream()
                .collect(Collectors.toUnmodifiableMap(AddonCatalogProperties.Entry::id, e -> e, (a, b) -> a));
    }

    /** 전체 catalog list — UI 가 checkbox 표시용. */
    public List<AddonCatalogProperties.Entry> list() {
        return addons;
    }

    /** id 기반 lookup. catalogId 가 null/blank 이면 empty. */
    public Optional<AddonCatalogProperties.Entry> find(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(catalogId));
    }
}
