package com.aipaas.anycloud.domain.helmrepo.internal;

import com.aipaas.anycloud.domain.helmrepo.HelmRepoRepository;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepoSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Helm repo 등록 통계 Micrometer expose.
 *
 * <p>{@code anycloud_helm_repo_count_total{source="INTERNAL|EXTERNAL"}} gauge. /actuator/prometheus
 * 또는 Grafana 가 source 별 추세 visualize 가능. air-gapped 운영 환경에서 unintended external
 * repo 가 등록됐는지 즉시 감지.
 *
 * <p>매 30초 마다 refresh — DB count() 만 호출하는 cheap query. CRUD event-driven update 도
 * 가능하나 metric 의 typical resolution (~분 단위) 에선 polling 으로 충분 + 동시성 단순.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelmRepoMetrics {

    private final HelmRepoRepository helmRepoRepository;
    private final MeterRegistry meterRegistry;

    private MultiGauge countBySource;

    @PostConstruct
    void init() {
        this.countBySource = MultiGauge.builder("anycloud.helm_repo.count")
                .description("Registered helm repository count by source")
                .baseUnit("repos")
                .register(meterRegistry);
        refresh(); // 즉시 한 번
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        try {
            // 30초마다 도는 스케줄이라 전체 row 를 읽어 개수만 세면 repo 수에 비례해 비용이 는다.
            // DB 에서 group by 로 집계한다.
            Map<HelmRepoSource, Long> bySource = new EnumMap<>(HelmRepoSource.class);
            for (HelmRepoSource s : HelmRepoSource.values()) {
                bySource.put(s, 0L);
            }
            for (Object[] row : helmRepoRepository.countGroupedBySource()) {
                HelmRepoSource s = row[0] == null ? HelmRepoSource.EXTERNAL : (HelmRepoSource) row[0];
                bySource.merge(s, ((Number) row[1]).longValue(), Long::sum);
            }
            java.util.List<Row<?>> rows = new java.util.ArrayList<>();
            for (Map.Entry<HelmRepoSource, Long> en : bySource.entrySet()) {
                Row<Number> row = Row.of(Tags.of("source", en.getKey().name()), en.getValue());
                rows.add(row);
            }
            countBySource.register(rows, true);
        } catch (Exception e) {
            log.warn("HelmRepoMetrics refresh failed: {}", e.toString());
        }
    }
}
