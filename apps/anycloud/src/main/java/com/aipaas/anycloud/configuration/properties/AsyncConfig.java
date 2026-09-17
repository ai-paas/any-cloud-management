package com.aipaas.anycloud.configuration.properties;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 도메인별 @Async 풀 (helm/kubernetes/provisioning/bootstrap). 큐 포화 시 {@link CallerRunsPolicy}
 * 로 호출자에 backpressure. default = kubernetesExecutor.
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig implements AsyncConfigurer {

    public static final String HELM_EXECUTOR = "helmExecutor";
    public static final String KUBERNETES_EXECUTOR = "kubernetesExecutor";
    public static final String PROVISIONING_EXECUTOR = "provisioningExecutor";
    public static final String BOOTSTRAP_EXECUTOR = "bootstrapExecutor";

    private final AsyncProperties properties;
    private final MeterRegistry meterRegistry;

    @Bean(name = HELM_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor helmExecutor() {
        return build(HELM_EXECUTOR, properties.getHelm());
    }

    @Bean(name = KUBERNETES_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor kubernetesExecutor() {
        return build(KUBERNETES_EXECUTOR, properties.getKubernetes());
    }

    @Bean(name = PROVISIONING_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor provisioningExecutor() {
        return build(PROVISIONING_EXECUTOR, properties.getProvisioning());
    }

    @Bean(name = BOOTSTRAP_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor bootstrapExecutor() {
        return build(BOOTSTRAP_EXECUTOR, properties.getBootstrap());
    }

    @Override
    public Executor getAsyncExecutor() {
        return kubernetesExecutor();
    }

    /** @Async uncaught handler: SLF4J 로깅 + Micrometer counter {@code async.exception}. */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            String klass = method.getDeclaringClass().getSimpleName();
            String name = method.getName();
            String paramSummary = paramsSummary(params);
            log.error("Async failure: {}#{}({})", klass, name, paramSummary, throwable);
            try {
                meterRegistry
                        .counter(
                                "async.exception",
                                "class",
                                klass,
                                "method",
                                name,
                                "exception",
                                throwable.getClass().getSimpleName())
                        .increment();
            } catch (Exception meterEx) {
                log.warn("Failed to increment async.exception counter: {}", meterEx.toString());
            }
        };
    }

    /** 파라미터 요약 (첫 5개 · 각 80자 cap). 민감값 회피 위해 toString 만. */
    private static String paramsSummary(Object[] params) {
        if (params == null || params.length == 0) return "";
        return Arrays.stream(params)
                .limit(5)
                .map(p -> p == null ? "null" : truncate(p.toString(), 80))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 풀 게이지를 {@link ThreadPoolTaskExecutor} 에 건다.
     *
     * <p>내부 {@code ThreadPoolExecutor} 에 직접 걸면 안 된다 — 스프링이 InitializingBean 으로
     * 초기화를 한 번 더 부르면서 그 객체를 새로 만들고, 버려진 객체는 회수된다. 게이지는 약한
     * 참조라 그 순간 전부 NaN 이 된다.
     */
    private void registerPoolGauges(ThreadPoolTaskExecutor executor, String poolName) {
        Tags tags = Tags.of("pool", poolName);
        // 이름과 단위는 Micrometer ExecutorServiceMetrics 규약 그대로 — 대시보드와 기존 검사가
        // 그 이름을 쓴다.
        gauge("executor.pool.core", "threads", executor, tags, ThreadPoolExecutor::getCorePoolSize);
        gauge("executor.pool.max", "threads", executor, tags, ThreadPoolExecutor::getMaximumPoolSize);
        gauge("executor.pool.size", "threads", executor, tags, ThreadPoolExecutor::getPoolSize);
        gauge("executor.active", "threads", executor, tags, ThreadPoolExecutor::getActiveCount);
        gauge("executor.queued", "tasks", executor, tags, tp -> tp.getQueue().size());
        gauge("executor.queue.remaining", "tasks", executor, tags, tp -> tp.getQueue()
                .remainingCapacity());
        gauge("executor.completed", "tasks", executor, tags, ThreadPoolExecutor::getCompletedTaskCount);
    }

    private void gauge(
            String name,
            String baseUnit,
            ThreadPoolTaskExecutor executor,
            Tags tags,
            java.util.function.ToDoubleFunction<ThreadPoolExecutor> read) {
        io.micrometer.core.instrument.Gauge.builder(name, executor, e -> read.applyAsDouble(e.getThreadPoolExecutor()))
                .tags(tags)
                .baseUnit(baseUnit)
                .register(meterRegistry);
    }

    private ThreadPoolTaskExecutor build(String poolName, AsyncProperties.Pool pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix(pool.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(
                pool.isRunOnCallerWhenFull()
                        ? new CallerRunsPolicy()
                        : new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // 큐가 가득 차야 core 를 넘어 늘어난다 — 실질 동시성은 coreSize 다
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(pool.getAwaitTerminationSeconds());
        // MDC 컨텍스트를 작업 스레드로 propagate — 작업 종료 시 원복 (pool reuse 누수 방지).
        executor.setTaskDecorator(MDC_PROPAGATING_DECORATOR);
        executor.initialize();
        registerPoolGauges(executor, poolName);
        log.info(
                "Initialized async pool '{}': core={}, max={}, queue={} (metrics: pool={})",
                pool.getThreadNamePrefix(),
                pool.getCoreSize(),
                pool.getMaxSize(),
                pool.getQueueCapacity(),
                poolName);
        return executor;
    }

    /** MDC propagation — 작업 종료 시 원복 (pool thread 재사용으로 인한 누수 차단). */
    private static final TaskDecorator MDC_PROPAGATING_DECORATOR = runnable -> {
        Map<String, String> caller = LoggingMdc.snapshot();
        return () -> {
            Map<String, String> previous = LoggingMdc.snapshot();
            LoggingMdc.restore(caller);
            try {
                runnable.run();
            } finally {
                LoggingMdc.restore(previous);
            }
        };
    };
}
