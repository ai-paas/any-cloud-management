package com.aipaas.anycloud.configuration.persistence;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine in-process cache. CSP catalog(regions, specs, images)처럼 호출 quota 가 있고 변경
 * 빈도가 낮은 데이터 대상.
 *
 * <p>프로세스 메모리라 replica 간 미공유. 즉시 반영이 필요한 값은 대상 외.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String VM_OPTIONS_REGIONS = "vmOptions.regions";
    public static final String VM_OPTIONS_SPECS = "vmOptions.specs";
    public static final String VM_OPTIONS_IMAGES = "vmOptions.images";

    /** config-schema 는 CSP API 다중 호출로 조립. 폼 진입마다 재조립 불필요. */
    public static final String VM_OPTIONS_CONFIG_SCHEMA = "vmOptions.configSchema";

    public static final String HELM_CHART_VALUES = "helm.chartValues";
    public static final String HELM_CHART_README = "helm.chartReadme";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                VM_OPTIONS_REGIONS,
                VM_OPTIONS_SPECS,
                VM_OPTIONS_IMAGES,
                VM_OPTIONS_CONFIG_SCHEMA,
                HELM_CHART_VALUES,
                HELM_CHART_README);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(1_000)
                .recordStats()); // Micrometer 가 cache.* metric 자동 노출
        return mgr;
    }
}
