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
 * 워크플로 단계 포화 시 처리 정책.
 *
 * <p>PROVISION, BOOTSTRAP, VERIFY, DESTROY 가 단일 풀 공유. 한 단계가 최대 170분간 스레드 점유.
 * 포화 시 호출 스레드에서 실행하면 해당 호출자가 AMQP 리스너이므로 그 큐의 메시지 소비가
 * 정지하고 ack 불가로 재전달 루프 진입.
 *
 * <p>거부 시 {@code WorkflowStepDispatcher} 가 락을 반납하고 메시지 재큐. 잠시 후 재도착.
 */
class BootstrapPoolSaturationTest extends AbstractUnitTest {

    private AsyncConfig configWith(AsyncProperties.Pool bootstrap) {
        AsyncProperties properties = new AsyncProperties();
        properties.setBootstrap(bootstrap);
        return new AsyncConfig(properties, new SimpleMeterRegistry());
    }

    /**
     * 실제 bootstrap 설정을 유지하고 크기만 축소. 거부 정책을 변경하지 않아야 운영 설정 검증
     * 효과 유지.
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
        // 큐 포화 시에만 core 초과 확장. 큐가 크면 max 미사용.
        AsyncProperties.Pool pool = new AsyncProperties().getBootstrap();

        assertThat(pool.getQueueCapacity()).as("큐가 크면 실질 동시성이 core 에 묶인다").isLessThanOrEqualTo(pool.getMaxSize());
    }

    @Test
    void sevenClustersFitWithRoomToSpare() {
        /*
         * 공인인증 시험은 CSP 7개를 동시 실행. 클러스터당 한 단계만 점유하므로 기본 7개이나,
         * 정체 단계 재발행과 삭제가 겹치면 증가.
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
