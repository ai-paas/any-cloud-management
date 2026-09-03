package com.aipaas.anycloud.domain.agent.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AgentHealthMetricsBinder} 단위 테스트. SimpleMeterRegistry 로 metric 등록 확인.
 *
 * <p>실제 scheduling 은 검증하지 않고 {@code init()} + {@code scan()} 을 직접 호출 — gauge
 * row 가 cluster 별로 등록됐는지 + 값이 ClusterHealth 와 일치하는지 확인.
 */
class AgentHealthMetricsBinderTest extends AbstractUnitTest {

    @Mock
    ClusterRepository clusterRepository;

    @Mock
    AgentHealthService agentHealthService;

    private MeterRegistry meterRegistry;
    private AgentHealthMetricsBinder binder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        binder = new AgentHealthMetricsBinder(clusterRepository, agentHealthService, meterRegistry);
        // @Value 가 동작하지 않는 단위 테스트라 직접 enable.
        ReflectionTestUtils.setField(binder, "enabled", true);
        binder.init();
    }

    @Test
    void scan_registersHealthyAndUnhealthyAndNoAgent() {
        ClusterEntity c1 = ClusterEntity.builder()
                .id("alpha")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        ClusterEntity c2 = ClusterEntity.builder()
                .id("bravo")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        ClusterEntity c3 = ClusterEntity.builder()
                .id("charlie")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        Page<ClusterEntity> page = new PageImpl<>(List.of(c1, c2, c3), Pageable.unpaged(), 3);
        when(clusterRepository.findAll(any(Pageable.class))).thenReturn(page);

        Instant now = Instant.parse("2026-05-19T12:00:00Z");
        when(agentHealthService.getHealth(eq("alpha")))
                .thenReturn(new ClusterHealth(
                        "alpha", true, "ok", "ACTIVE", true, now.minusSeconds(3), now.minusSeconds(3), 3L));
        when(agentHealthService.getHealth(eq("bravo")))
                .thenReturn(new ClusterHealth(
                        "bravo", false, "stale", "DEGRADED", false, now.minusSeconds(120), null, 120L));
        when(agentHealthService.getHealth(eq("charlie"))).thenReturn(ClusterHealth.noAgent("charlie"));

        binder.scan();

        // healthy gauge — alpha=1, bravo=0, charlie=0
        assertThat(meterRegistry
                        .get("anycloud.agent.healthy")
                        .tag("cluster", "alpha")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.healthy")
                        .tag("cluster", "bravo")
                        .gauge()
                        .value())
                .isEqualTo(0.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.healthy")
                        .tag("cluster", "charlie")
                        .gauge()
                        .value())
                .isEqualTo(0.0d);

        // heartbeat age — charlie 는 신호 없음 → -1
        assertThat(meterRegistry
                        .get("anycloud.agent.heartbeat.age.seconds")
                        .tag("cluster", "alpha")
                        .gauge()
                        .value())
                .isEqualTo(3.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.heartbeat.age.seconds")
                        .tag("cluster", "bravo")
                        .gauge()
                        .value())
                .isEqualTo(120.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.heartbeat.age.seconds")
                        .tag("cluster", "charlie")
                        .gauge()
                        .value())
                .isEqualTo(-1.0d);

        // stream active — alpha=1, others=0
        assertThat(meterRegistry
                        .get("anycloud.agent.stream.active")
                        .tag("cluster", "alpha")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.stream.active")
                        .tag("cluster", "bravo")
                        .gauge()
                        .value())
                .isEqualTo(0.0d);

        // status — per cluster + per status label, current=1
        assertThat(meterRegistry
                        .get("anycloud.agent.status")
                        .tags("cluster", "alpha", "status", "ACTIVE")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.status")
                        .tags("cluster", "bravo", "status", "DEGRADED")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
        assertThat(meterRegistry
                        .get("anycloud.agent.status")
                        .tags("cluster", "charlie", "status", "NONE")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
    }

    @Test
    void scan_skipsClusterOnLookupFailure() {
        ClusterEntity c1 = ClusterEntity.builder()
                .id("good")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        ClusterEntity c2 = ClusterEntity.builder()
                .id("bad")
                .clusterType("k8s")
                .clusterProvider("AWS")
                .build();
        Page<ClusterEntity> page = new PageImpl<>(List.of(c1, c2), Pageable.unpaged(), 2);
        when(clusterRepository.findAll(any(Pageable.class))).thenReturn(page);

        when(agentHealthService.getHealth(eq("good")))
                .thenReturn(new ClusterHealth("good", true, "ok", "ACTIVE", true, Instant.now(), Instant.now(), 1L));
        when(agentHealthService.getHealth(eq("bad"))).thenThrow(new RuntimeException("simulated"));

        binder.scan();

        // 한 cluster 실패가 나머지 metric 등록을 막지 않아야 한다.
        assertThat(meterRegistry
                        .get("anycloud.agent.healthy")
                        .tag("cluster", "good")
                        .gauge()
                        .value())
                .isEqualTo(1.0d);
        // bad cluster 는 row 미등록.
        assertThat(meterRegistry
                        .find("anycloud.agent.healthy")
                        .tag("cluster", "bad")
                        .gauge())
                .isNull();
    }

    @Test
    void disabled_doesNotRegisterGauges() {
        ReflectionTestUtils.setField(binder, "enabled", false);
        // fresh binder 인 척 — 실제로는 init 이 이미 호출됐으니, scan 만 noop 확인.
        binder.scan();
        // scan 이 enabled=false 일 때 register 호출하지 않음 → 새 row 없음. 기존 메트릭 자체는 init 단계
        // 에서 register 됐을 수 있으나 row=0 이므로 .gauges() 가 비어 있어야 함.
        assertThat(meterRegistry.find("anycloud.agent.healthy").gauges()).isEmpty();
    }
}
