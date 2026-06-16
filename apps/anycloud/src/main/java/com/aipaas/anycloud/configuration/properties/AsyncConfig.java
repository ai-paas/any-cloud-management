package com.aipaas.anycloud.configuration.properties;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;
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

    private ThreadPoolTaskExecutor build(String poolName, AsyncProperties.Pool pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix(pool.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(pool.getAwaitTerminationSeconds());
        // MDC 컨텍스트를 작업 스레드로 propagate — 작업 종료 시 원복 (pool reuse 누수 방지).
        executor.setTaskDecorator(MDC_PROPAGATING_DECORATOR);
        executor.initialize();
        // Micrometer 메트릭 (executor.active/queued/queue.remaining/pool.size/completed).
        ExecutorServiceMetrics.monitor(
                meterRegistry, executor.getThreadPoolExecutor(), poolName, Tags.of("pool", poolName));
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
