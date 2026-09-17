package com.aipaas.anycloud.configuration.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 워크플로 단계가 넘칠 때 무엇을 희생할지.
 *
 * <p>PROVISION, BOOTSTRAP, VERIFY, DESTROY 가 이 풀 하나를 같이 쓴다. 한 단계는 최악 170분까지
 * 스레드를 잡는다. 넘쳤을 때 호출자 스레드에서 실행하면 그 호출자는 AMQP 리스너다 — 그 큐의
 * 메시지 소비가 통째로 멈추고, ack 를 못 해 재전달 루프가 돌아온다.
 *
 * <p>거부하면 {@code WorkflowStepDispatcher} 가 락을 반납하고 메시지를 되돌린다. 잠시 뒤 다시 온다.
 */
class BootstrapPoolSaturationTest extends AbstractUnitTest {

    private AsyncConfig configWith(AsyncProperties.Pool bootstrap) {
        AsyncProperties properties = new AsyncProperties();
        properties.setBootstrap(bootstrap);
        return new AsyncConfig(properties, new SimpleMeterRegistry());
    }

    /**
     * 실제 bootstrap 설정을 그대로 쓰되 크기만 줄인다 — 거부 정책은 건드리지 않아야 이 검사가
     * 진짜 운영 설정을 검증한다.
     */
    private AsyncProperties.Pool tinyPool() {
        AsyncProperties.Pool pool = new AsyncProperties().getBootstrap();
        pool.setCoreSize(1);
        pool.setMaxSize(1);
        pool.setQueueCapacity(0);
        return pool;
    }

    @Test
    void anOverflowingStepIsRejectedInsteadOfRunningOnTheCaller() throws Exception {
        ThreadPoolTaskExecutor executor = configWith(tinyPool()).bootstrapExecutor();
        CountDownLatch hold = new CountDownLatch(1);
        AtomicReference<String> ranOn = new AtomicReference<>();
        executor.execute(() -> await(hold));

        try {
            assertThatThrownBy(() -> executor.execute(
                            () -> ranOn.set(Thread.currentThread().getName())))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(ranOn.get()).as("호출자 스레드에서 실행되면 안 된다").isNull();
        } finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    void theRealLimitIsCoreSizeNotMaxSize() {
        // 큐가 가득 차야 core 를 넘어 늘어난다. 큐가 크면 max 는 영영 쓰이지 않는다.
        AsyncProperties.Pool pool = new AsyncProperties().getBootstrap();

        assertThat(pool.getQueueCapacity()).as("큐가 크면 실질 동시성이 core 에 묶인다").isLessThanOrEqualTo(pool.getMaxSize());
    }

    @Test
    void sevenClustersFitWithRoomToSpare() {
        /*
         * 공인인증 시험은 CSP 7개를 동시에 돌린다. 클러스터 하나는 한 단계만 점유하므로 7개지만,
         * 정체된 단계를 되살리는 재발행과 삭제가 겹치면 그보다 늘어난다.
         */
        assertThat(new AsyncProperties().getBootstrap().getCoreSize())
                .as("동시 프로비저닝 7 + 재발행·삭제 여유")
                .isGreaterThanOrEqualTo(14);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
