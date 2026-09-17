package com.aipaas.anycloud.configuration.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 풀이 얼마나 차 있는지 보이지 않으면 포화를 사후에야 안다.
 *
 * <p>{@code ThreadPoolTaskExecutor} 는 {@code InitializingBean} 이라 스프링이 초기화를 한 번 더
 * 부른다. 그때 내부 {@code ThreadPoolExecutor} 가 새로 만들어지는데, 미터를 그 이전 객체에 걸어
 * 두면 그 객체는 버려져 회수되고 게이지는 약한 참조라 전부 NaN 이 된다 — 실제로 그랬다.
 */
class PoolMetricsSurviveSpringInitTest extends AbstractUnitTest {

    private double gaugeValue(SimpleMeterRegistry registry, String name) {
        Gauge gauge = registry.find(name).tag("pool", "bootstrapExecutor").gauge();
        assertThat(gauge).as("%s 게이지가 없다", name).isNotNull();
        return gauge.value();
    }

    @Test
    void poolGaugesStillReportAfterSpringInitializesAgain() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncProperties properties = new AsyncProperties();
        ThreadPoolTaskExecutor executor = new AsyncConfig(properties, registry).bootstrapExecutor();

        // 스프링이 InitializingBean 으로 한 번 더 부른다 — 내부 풀이 새로 만들어진다.
        executor.afterPropertiesSet();
        System.gc();

        try {
            assertThat(gaugeValue(registry, "executor.pool.core"))
                    .isEqualTo(properties.getBootstrap().getCoreSize());
            assertThat(gaugeValue(registry, "executor.pool.max"))
                    .isEqualTo(properties.getBootstrap().getMaxSize());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void queueDepthIsVisible() {
        // 7개 동시 프로비저닝에서 큐가 쌓이는지 보려면 이 값이 필요하다.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new AsyncConfig(new AsyncProperties(), registry).bootstrapExecutor();
        executor.afterPropertiesSet();
        System.gc();

        try {
            assertThat(gaugeValue(registry, "executor.queued")).isNotNaN();
        } finally {
            executor.shutdown();
        }
    }
}
