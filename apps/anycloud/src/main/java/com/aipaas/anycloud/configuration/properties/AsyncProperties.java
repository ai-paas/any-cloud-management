package com.aipaas.anycloud.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 도메인별 비동기 풀 설정. 풀 분리로 한 도메인의 부하가 다른 도메인 작업을 고갈시키지 않도록
 * 처리. 기본값은 아래 필드 선언이 단일 출처이고 {@code async.pools.*} 로 재정의.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "async")
public class AsyncProperties {

    private Pool helm = Pool.defaults("helm-", 5, 10, 50);
    private Pool kubernetes = Pool.defaults("k8s-", 4, 8, 100);
    /*
     * AMQP 비활성 로컬 경로(LocalVmClusterWorkflowPublisherImpl)에서 워크플로 4단계가 사용.
     * AMQP 활성 시 미사용. 두 경로의 동시성이 다르면 로컬에서만 재현되는 포화 발생.
     */
    private Pool provisioning = Pool.rejecting("pulumi-", 16, 24, 8);
    /*
     * 워크플로 4단계(PROVISION/BOOTSTRAP/VERIFY/DESTROY)가 단일 풀 공유. 한 단계가 최대 170분간
     * 스레드를 점유하므로 동시 프로비저닝 수가 필요 스레드 수와 동일.
     * 큐 크기는 max 이하. 큐가 크면 core 초과 확장이 일어나지 않아 max 미사용.
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
         * 큐 포화 시 호출 스레드 실행 여부.
         *
         * <p>워크플로 풀은 false 고정. 호출자가 AMQP 리스너이므로 170분 작업 실행 시 해당 큐의
         * 소비가 정지하고 ack 불가로 재전달 루프 진입. 거부 시 dispatcher 가 락을 반납하고
         * 메시지 재큐.
         */
        private boolean runOnCallerWhenFull = true;

        /** 넘치면 거부하는 풀: 호출자를 붙잡으면 안 되는 자리. */
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
