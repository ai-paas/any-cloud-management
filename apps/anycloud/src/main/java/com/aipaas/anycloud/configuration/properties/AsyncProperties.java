package com.aipaas.anycloud.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 도메인별 비동기 풀 설정.
 * <p>
 * 각 풀은 독립된 {@code ThreadPoolTaskExecutor} 로 구성되어 한 도메인의 부하 폭주가
 * 다른 도메인의 비동기 작업을 굶기지 않도록 격리.
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
    /*
     * AMQP 를 끈 로컬 경로(LocalVmClusterWorkflowPublisherImpl)에서 워크플로 4단계가 여기로 간다.
     * AMQP 를 켜면 쓰이지 않는다. 두 경로의 동시성이 다르면 로컬에서만 재현되는 포화가 생긴다.
     */
    private Pool provisioning = Pool.rejecting("pulumi-", 16, 24, 8);
    /*
     * 워크플로 4단계(PROVISION/BOOTSTRAP/VERIFY/DESTROY)가 이 풀 하나를 같이 쓴다. 한 단계가 최악
     * 170분까지 스레드를 잡으므로, 동시 프로비저닝 수가 곧 필요한 스레드 수다.
     * 큐를 max 이하로 두어야 core 를 넘겨 늘어난다 — 큐가 크면 max 는 쓰이지 않는다.
     */
    private Pool bootstrap = Pool.rejecting("bootstrap-", 16, 24, 8);

    @Getter
    @Setter
    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private String threadNamePrefix;
        /** graceful shutdown 대기 시간(초). */
        private int awaitTerminationSeconds = 30;
        /** 유휴 스레드 회수 대기(초). */
        private int keepAliveSeconds = 60;

        /**
         * 넘쳤을 때 호출자 스레드에서 실행할지.
         *
         * <p>워크플로 풀은 false 여야 한다 — 호출자가 AMQP 리스너라, 거기서 170분짜리 작업을
         * 돌리면 그 큐의 소비가 멈추고 ack 를 못 해 재전달 루프가 돌아온다. 거부하면 dispatcher 가
         * 락을 반납하고 메시지를 되돌린다.
         */
        private boolean runOnCallerWhenFull = true;

        /** 넘치면 거부하는 풀 — 호출자를 붙잡으면 안 되는 자리. */
        public static Pool rejecting(String prefix, int core, int max, int queue) {
            Pool p = defaults(prefix, core, max, queue);
            p.runOnCallerWhenFull = false;
            return p;
        }

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
