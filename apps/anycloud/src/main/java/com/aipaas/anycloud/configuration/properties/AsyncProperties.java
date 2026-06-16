package com.aipaas.anycloud.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 도메인별 비동기 풀 설정.
 * <p>
 * 각 풀은 독립된 {@code ThreadPoolTaskExecutor} 로 구성되어 한 도메인의 부하 폭주가
 * 다른 도메인의 비동기 작업을 굶기지 않도록 격리한다.
 *
 * <pre>
 * async:
 *   pools:
 *     helm:         { core-size: 5, max-size: 10, queue-capacity: 50,  thread-name-prefix: helm- }
 *     kubernetes:   { core-size: 4, max-size: 8,  queue-capacity: 100, thread-name-prefix: k8s- }
 *     provisioning: { core-size: 3, max-size: 6,  queue-capacity: 30,  thread-name-prefix: pulumi- }
 *     bootstrap:    { core-size: 3, max-size: 6,  queue-capacity: 30,  thread-name-prefix: bootstrap- }
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "async")
public class AsyncProperties {

    private Pool helm = Pool.defaults("helm-", 5, 10, 50);
    private Pool kubernetes = Pool.defaults("k8s-", 4, 8, 100);
    private Pool provisioning = Pool.defaults("pulumi-", 3, 6, 30);
    private Pool bootstrap = Pool.defaults("bootstrap-", 3, 6, 30);

    @Getter
    @Setter
    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private String threadNamePrefix;
        /** graceful shutdown 대기 시간(초). */
        private int awaitTerminationSeconds = 30;

        public static Pool defaults(String prefix, int core, int max, int queue) {
            Pool p = new Pool();
            p.threadNamePrefix = prefix;
            p.coreSize = core;
            p.maxSize = max;
            p.queueCapacity = queue;
            return p;
        }
    }
}
