package com.aipaas.anycloud.configuration.persistence;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine in-process cache 입니다. CSP catalog(regions, specs, images)처럼 호출 quota 가 있고 잘
 * 변하지 않는 데이터를 담습니다.
 *
 * <p>프로세스 메모리라 replica 끼리 공유하지 않습니다. 즉시 반영돼야 하는 값은 넣지 마세요.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String VM_OPTIONS_REGIONS = "vmOptions.regions";
    public static final String VM_OPTIONS_SPECS = "vmOptions.specs";
    public static final String VM_OPTIONS_IMAGES = "vmOptions.images";

    /** config-schema 는 CSP API 를 여러 번 불러 조립합니다. 폼을 열 때마다 다시 할 이유가 없습니다. */
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
