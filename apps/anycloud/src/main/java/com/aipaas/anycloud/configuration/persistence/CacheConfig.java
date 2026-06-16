package com.aipaas.anycloud.configuration.persistence;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine 기반 in-process cache.
 * <p>
 * 적용 대상은 외부 API 호출이 비싼 read-mostly 데이터:
 * <ul>
 *   <li>provider catalog (regions / specs / images) — AWS describe* / GCP list* 등은 시간당 호출
 *       quota 가 있고 데이터가 분/시간 단위로 거의 변하지 않음.</li>
 *   <li>helm chart metadata — index.yaml 은 helm repo 가 분 단위로 갱신되지만 chart values /
 *       readme 는 unsigned read 라 cache OK.</li>
 * </ul>
 * <p>
 * cache eviction: TTL 30분 (catalog 류는 더 길게도 무방하지만 안전).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String VM_OPTIONS_REGIONS = "vmOptions.regions";
    public static final String VM_OPTIONS_SPECS = "vmOptions.specs";
    public static final String VM_OPTIONS_IMAGES = "vmOptions.images";
    public static final String HELM_CHART_VALUES = "helm.chartValues";
    public static final String HELM_CHART_README = "helm.chartReadme";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                VM_OPTIONS_REGIONS, VM_OPTIONS_SPECS, VM_OPTIONS_IMAGES, HELM_CHART_VALUES, HELM_CHART_README);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(1_000)
                .recordStats()); // Micrometer 가 cache.* metric 자동 노출
        return mgr;
    }
}
