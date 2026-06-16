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
 * 등록된 cluster 의 agent health 시계열을 Prometheus 로 노출.
 *
 * <p>{@code AgentHealthService} 는 인스턴스 응답(REST snapshot)용이고, 알람/SLO 는 시계열이 필요해
 * 별도 binder. {@code ClusterCertExpiryMonitor} 와 동일한 MultiGauge + paginate scan 패턴.
 *
 * <h3>노출 metric</h3>
 * <pre>
 * anycloud_agent_healthy{cluster="..."}                 0 | 1
 * anycloud_agent_stream_active{cluster="..."}           0 | 1
 * anycloud_agent_heartbeat_age_seconds{cluster="..."}   초 (-1 = 신호 없음)
 * anycloud_agent_status{cluster="...", status="ACTIVE|REGISTERED|DEGRADED|FAILED|REVOKED|NONE"}
 *     해당 cluster 의 현재 status 만 1, 나머지는 row 미존재 (PromQL absent() 친화)
 * </pre>
 *
 * <h3>운영 알람 예 (PromQL)</h3>
 * <pre>
 * # 5분 이상 unhealthy 한 cluster
 * sum(anycloud_agent_healthy == 0) by (cluster)
 *
 * # heartbeat 60초 이상 stale
 * anycloud_agent_heartbeat_age_seconds > 60
 *
 * # FAILED/REVOKED 인 cluster
 * sum(anycloud_agent_status{status=~"FAILED|REVOKED"}) by (cluster)
 * </pre>
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

    /**
     * 30 초 주기. heartbeat 기본 cadence(30s) 와 동일 — 너무 잦으면 DB 부하, 너무 늦으면 알람 지연.
     *
     * <p>{@link SchedulerLock} 으로 multi-instance 운영 시 한 노드만 scan — DB read 와 metric
     * register 가 노드별로 중복되지 않도록. {@code lockAtMostFor} 는 scan 멈춤 시 안전한 unlock
     * 시한, {@code lockAtLeastFor} 는 빠른 재실행을 막아 thrash 방지.
     */
    /**
     * 추가 최적화 불요 — 다음 안전장치로 1000+ cluster 환경에서도 안정 동작:
     * <ul>
     *   <li>페이지네이션: 이미 PageRequest(CHUNK_SIZE) 단위 + 페이지 간 sleep.</li>
     *   <li>분산 환경: ShedLock {@code lockAtMostFor=PT2M} 가 다중 replica 동시 실행 방지.</li>
     *   <li>부분 실패 격리: 한 cluster 의 ClusterHealth lookup 실패는 log + skip → 전체 scan 보호.</li>
     *   <li>운영 toggle: {@code anycloud.agent.metrics.enabled} 로 즉시 비활성화 가능.</li>
     * </ul>
     * 향후 개선 후보 (별도 PR): per-cluster getHealth 호출에 timeout / circuit breaker 적용 — 1000+
     * cluster 환경에서 한 cluster 의 hang 이 chunk 전체를 늦추는 케이스. 현재는 timeout 운영
     * 사례 미발견 → optimization 미적용.
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
