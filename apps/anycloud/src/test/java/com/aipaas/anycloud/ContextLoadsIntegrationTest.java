package com.aipaas.anycloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 전체 Spring context 부팅 smoke. 이 한 테스트가 깨지면 어딘가의 autowiring 이 망가진 것.
 * <p>
 * 검증 포인트:
 * <ul>
 *   <li>AnycloudApplication 의 모든 bean 이 instantiate 됨 (RabbitMQ / Pulumi / Flyway 는 test profile 에서 off)</li>
 *   <li>Micrometer MeterRegistry 가 등록됨 (S2 의 async pool 메트릭 + A2 의 resilience4j 메트릭 의존)</li>
 *   <li>4 개 async pool 의 metric 이 노출됨</li>
 * </ul>
 */
class ContextLoadsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void contextLoads() {
        assertThat(ctx).isNotNull();
        assertThat(ctx.getBean("vmClusterServiceImpl")).isNotNull();
    }

    @Test
    void asyncPoolMetricsRegistered() {
        // S2 회귀 방지: 4 개 pool 모두 ExecutorServiceMetrics 바인딩.
        assertThat(meterRegistry
                        .find("executor.queue.remaining")
                        .tag("pool", "helmExecutor")
                        .gauge())
                .isNotNull();
        assertThat(meterRegistry
                        .find("executor.queue.remaining")
                        .tag("pool", "kubernetesExecutor")
                        .gauge())
                .isNotNull();
        assertThat(meterRegistry
                        .find("executor.queue.remaining")
                        .tag("pool", "provisioningExecutor")
                        .gauge())
                .isNotNull();
        assertThat(meterRegistry
                        .find("executor.queue.remaining")
                        .tag("pool", "bootstrapExecutor")
                        .gauge())
                .isNotNull();
    }

    @Test
    void resilience4jMetricsRegistered() {
        // A2 회귀 방지: kubernetes CB + pulumi bulkhead.
        assertThat(meterRegistry
                        .find("resilience4j.circuitbreaker.state")
                        .tag("name", "kubernetes")
                        .gauges())
                .isNotEmpty();
        assertThat(meterRegistry
                        .find("resilience4j.bulkhead.available.concurrent.calls")
                        .tag("name", "pulumi")
                        .gauge())
                .isNotNull();
    }
}
