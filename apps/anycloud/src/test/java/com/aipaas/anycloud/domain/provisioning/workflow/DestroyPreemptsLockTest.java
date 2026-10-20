package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * 삭제는 앞 단계를 대기하지 않음.
 *
 * <p>stale 창을 단계 예산(BOOTSTRAP 170분)에 맞추면 삭제가 그 뒤에 대기. 정지된 프로비저닝
 * 삭제에 3시간 소요. 삭제는 사용자의 복구 수단.
 *
 * <p>앞 단계 결과는 폐기 대상이므로 선점해도 손실 없음.
 */
class DestroyPreemptsLockTest extends AbstractUnitTest {

    @Mock
    VmClusterRepository vmClusterRepository;

    private ProcessingLock lock;

    @BeforeEach
    void setUp() {
        lock = new ProcessingLock(vmClusterRepository, Duration.ofMinutes(180));
    }

    @Test
    void destroyTakesOverEvenWhileAnotherStepHoldsTheLock() {
        when(vmClusterRepository.takeOverProcessing(eq("c1"), eq("m-destroy"), any()))
                .thenReturn(1);

        assertThat(lock.preempt("c1", "m-destroy")).isTrue();
        verify(vmClusterRepository).takeOverProcessing(eq("c1"), eq("m-destroy"), any());
    }

    @Test
    void preemptDoesNotFallBackToTheNormalGuard() {
        // 일반 acquire 로 떨어지면 다시 줄을 선다.
        lock.preempt("c1", "m-destroy");

        verify(vmClusterRepository, org.mockito.Mockito.never()).acquireProcessing(any(), any(), any(), any());
    }

    @Test
    void nothingToLockIsStillAllowed() {
        // 잠금 대상이 없는 destroy 흐름(스택만 잔존)을 차단하면 정리 수단 부재.
        assertThat(lock.preempt(null, "m-destroy")).isTrue();
        assertThat(lock.preempt("c1", null)).isTrue();
    }
}
