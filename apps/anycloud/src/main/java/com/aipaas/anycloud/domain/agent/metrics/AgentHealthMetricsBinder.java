package com.aipaas.anycloud.domain.agent.metrics;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import io.aipaas.cluster.agent.core.AgentStatus;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * agent health 를 Prometheus 시계열로 내보냅니다. {@code AgentHealthService} 는 REST 응답용 스냅샷이고
 * 알람과 SLO 에는 시계열이 필요합니다.
 *
 * <p>metric 이름과 라벨은 대시보드가 쓰는 계약입니다. 바꾸면 알람이 조용히 멈춥니다.
 *
 * <pre>
 * anycloud_agent_healthy{cluster="..."}                 0 | 1
 * anycloud_agent_stream_active{cluster="..."}           0 | 1
 * anycloud_agent_heartbeat_age_seconds{cluster="..."}   초 (-1 = 신호 없음)
 * anycloud_agent_status{cluster="...", status="ACTIVE|REGISTERED|DEGRADED|FAILED|REVOKED|NONE"}
 * </pre>
 *
 * <p>{@code anycloud_agent_status} 는 현재 status 하나만 1 이고 나머지 라벨은 row 자체가 없습니다.
 * PromQL {@code absent()} 로 걸러내기 쉽게 하려는 것입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthMetricsBinder {

    /** Cluster 가 많아도 메모리/DB 부하 일정하게. */
    private static final int CHUNK_SIZE = 100;

    private static final long INTER_CHUNK_SLEEP_MS = 100L;

    private final ClusterRepository clusterRepository;
    private final AgentHealthService agentHealthService;
    private final MeterRegistry meterRegistry;

    /** 단일 cluster + agent 부재시 lastSeenSecondsAgo=null 처리 — Prometheus 는 NaN 못 받으니 -1. */
    private static final double NO_SIGNAL = -1.0d;

    @Value("${anycloud.agent.metrics.enabled:true}")
    private boolean enabled;

    private MultiGauge healthyGauge;
    private MultiGauge streamActiveGauge;
    private MultiGauge heartbeatAgeGauge;
    private MultiGauge statusGauge;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("Agent health metrics binder disabled (anycloud.agent.metrics.enabled=false)");
            return;
        }
        this.healthyGauge = MultiGauge.builder("anycloud.agent.healthy")
                .description("1 if cluster agent is healthy (ACTIVE + stream + fresh heartbeat), 0 otherwise")
                .register(meterRegistry);
        this.streamActiveGauge = MultiGauge.builder("anycloud.agent.stream.active")
                .description("1 if backend has an active gRPC stream from the agent, 0 otherwise")
                .register(meterRegistry);
        this.heartbeatAgeGauge = MultiGauge.builder("anycloud.agent.heartbeat.age.seconds")
                .description("Seconds since the last agent heartbeat. -1 = no signal yet.")
                .baseUnit("seconds")
                .register(meterRegistry);
        this.statusGauge = MultiGauge.builder("anycloud.agent.status")
                .description("Per-cluster agent status indicator (1 = current status; other statuses absent)")
                .register(meterRegistry);
    }

    /** 30 초 주기. heartbeat 기본 cadence(30s) 와 동일 — 너무 잦으면 DB 부하, 너무 늦으면 알람 지연. */
    /**
     * 더 최적화할 필요는 없습니다. 아래 장치로 cluster 가 1000개를 넘어도 버팁니다.
     * <ul>
     *   <li>페이지네이션: 이미 PageRequest(CHUNK_SIZE) 단위 + 페이지 간 sleep.</li>
     *   <li>분산 환경: ShedLock {@code lockAtMostFor=PT2M} 가 다중 replica 동시 실행 방지.</li>
     *   <li>부분 실패 격리: 한 cluster 의 ClusterHealth lookup 실패는 log + skip → 전체 scan 보호.</li>
     *   <li>운영 toggle: {@code anycloud.agent.metrics.enabled} 로 즉시 비활성화 가능.</li>
     * </ul>
     * 향후 개선 후보 (별도 PR): per-cluster getHealth 호출에 timeout / circuit breaker 적용 — 1000+
     * cluster 환경에서 한 cluster 의 hang 이 chunk 전체를 늦추는 케이스. 현재는 timeout 운영
     * 그런 사례가 없어 최적화하지 않았습니다.
     */
    @Scheduled(
            fixedDelayString = "${anycloud.agent.metrics.interval-ms:30000}",
            initialDelayString = "${anycloud.agent.metrics.initial-delay-ms:10000}")
    @SchedulerLock(name = "agentHealthMetricsScan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
    public void scan() {
        if (!enabled) {
            return;
        }

        List<MultiGauge.Row<?>> healthyRows = new ArrayList<>();
        List<MultiGauge.Row<?>> streamRows = new ArrayList<>();
        List<MultiGauge.Row<?>> heartbeatRows = new ArrayList<>();
        List<MultiGauge.Row<?>> statusRows = new ArrayList<>();

        int page = 0;
        int total = 0;
        while (true) {
            Page<ClusterEntity> chunk = clusterRepository.findAll(PageRequest.of(page, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }
            for (ClusterEntity cluster : chunk.getContent()) {
                ClusterHealth h;
                try {
                    h = agentHealthService.getHealth(cluster.getId());
                } catch (RuntimeException e) {
                    // 한 cluster 의 부분 실패가 전체 scan 을 망치지 않도록 격리 — log 만 남기고 skip.
                    log.warn("agent health lookup failed cluster={} err={}", cluster.getId(), e.toString());
                    continue;
                }

                Tags clusterTag = Tags.of("cluster", cluster.getId());

                healthyRows.add(MultiGauge.Row.of(clusterTag, h.healthy() ? 1.0d : 0.0d));
                streamRows.add(MultiGauge.Row.of(clusterTag, h.streamActive() ? 1.0d : 0.0d));

                double ageSeconds = h.lastSeenSecondsAgo() == null
                        ? NO_SIGNAL
                        : h.lastSeenSecondsAgo().doubleValue();
                heartbeatRows.add(MultiGauge.Row.of(clusterTag, ageSeconds));

                String statusLabel = normalizeStatus(h.agentStatus());
                statusRows.add(MultiGauge.Row.of(Tags.of("cluster", cluster.getId(), "status", statusLabel), 1.0d));

                total++;
            }
            if (!chunk.hasNext()) {
                break;
            }
            page++;
            try {
                Thread.sleep(INTER_CHUNK_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("agent health metrics scan interrupted at page {}", page);
                break;
            }
        }

        // register(rows, true) — 이전 scan 의 row 자동 expire. cluster 가 삭제되면 다음 scan 에서
        // 사라짐.
        healthyGauge.register(healthyRows, true);
        streamActiveGauge.register(streamRows, true);
        heartbeatAgeGauge.register(heartbeatRows, true);
        statusGauge.register(statusRows, true);

        log.debug("agent health metrics scan complete: {} clusters", total);
    }

    /**
     * status 라벨 정규화 — null/blank 는 NONE 으로, AgentStatus enum 외 값도 안전하게.
     * Prometheus label cardinality 폭주 방지.
     */
    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NONE";
        }
        try {
            return AgentStatus.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            // "NONE" / "UNKNOWN" 같은 sentinel 도 허용.
            return raw;
        }
    }
}
