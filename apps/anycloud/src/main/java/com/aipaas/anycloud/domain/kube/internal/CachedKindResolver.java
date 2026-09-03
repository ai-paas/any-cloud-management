package com.aipaas.anycloud.domain.kube.internal;

import com.aipaas.anycloud.domain.kube.KindResolver;
import com.aipaas.anycloud.domain.kube.model.K8sKinds;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link KindResolver} 의 Caffeine cache 기반 구현.
 *
 * <p>cache key = {@code clusterName + "|" + lowercased(input)}. cluster 별 격리 + 입력 정규화
 * (대소문자, shortname 등 다양한 표기 모두 동일 entry hit).
 *
 * <p>TTL 30분 — schema 가 거의 불변이므로 staleness 무시 가능. CRD 변동 직후엔
 * {@link #invalidate} 로 강제 refresh (addon install hook 등).
 */
@Slf4j
@Component
public class CachedKindResolver implements KindResolver {

    /** kind metadata 의 schema 변동 빈도 vs 사용자가 보는 freshness 의 trade-off. 30분이 sweet spot. */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final int MAX_ENTRIES = 5_000; // cluster 10개 × kind 500개 + 여유.

    private final KubeResourceService kubeResourceService;
    private final Cache<String, ResolvedResource> cache;

    public CachedKindResolver(KubeResourceService kubeResourceService) {
        this.kubeResourceService = kubeResourceService;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .recordStats()
                .build();
    }

    @Override
    public ResolvedResource resolve(String clusterName, String kindOrPluralOrShort) {
        if (clusterName == null
                || clusterName.isBlank()
                || kindOrPluralOrShort == null
                || kindOrPluralOrShort.isBlank()) {
            return null;
        }
        String key = cacheKey(clusterName, kindOrPluralOrShort);
        return cache.get(key, k -> resolveUncached(clusterName, kindOrPluralOrShort));
    }

    @Override
    public void invalidate(String clusterName) {
        if (clusterName == null) return;
        String prefix = clusterName + "|";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        log.info("KindResolver: invalidated cache for cluster={}", clusterName);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("KindResolver: invalidated all cluster caches");
    }

    // ---- internal ----

    /**
     * agent RESOLVE_RESOURCE 호출 → 응답 그대로 반환. RPC 실패 시 hardcoded fallback.
     * 반환 null 안함 — 미지 kind 도 best-effort placeholder.
     */
    private ResolvedResource resolveUncached(String clusterName, String input) {
        try {
            ResolvedResource res = kubeResourceService.resolveResource(clusterName, input);
            log.debug(
                    "KindResolver: resolved (agent) cluster={} input={} → plural={} namespaced={}",
                    clusterName,
                    input,
                    res.plural(),
                    res.namespaced());
            return res;
        } catch (Exception e) {
            log.warn(
                    "KindResolver: agent resolve failed cluster={} input={} — fallback to static set: {}",
                    clusterName,
                    input,
                    e.toString());
            return fallback(input);
        }
    }

    /**
     * agent 무응답 시 hardcoded {@link K8sKinds#CLUSTER_SCOPED} 만으로 best-effort.
     * namespaced flag 만 의미 있음 — plural/group/version 은 입력 그대로 (caller 가 그대로 forward).
     */
    private static ResolvedResource fallback(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        boolean clusterScoped = K8sKinds.isClusterScoped(lower);
        return new ResolvedResource(
                lower, // plural — 입력 그대로 (정규화 미보장)
                null, // singular
                null, // kind
                "", // group — caller 가 명시 안하면 core
                "v1", // version — best guess
                !clusterScoped, // namespaced
                List.of());
    }

    private static String cacheKey(String clusterName, String input) {
        return clusterName + "|" + input.toLowerCase(Locale.ROOT);
    }
}
