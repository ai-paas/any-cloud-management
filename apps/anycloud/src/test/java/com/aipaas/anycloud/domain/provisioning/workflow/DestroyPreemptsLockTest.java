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
 * 삭제는 앞 단계를 기다리지 않는다.
 *
 * <p>stale 창을 단계 예산(BOOTSTRAP 170분)에 맞추자 삭제가 그 뒤에 줄을 섰다 — 멈춘 프로비저닝을
 * 지우려는데 3시간을 기다려야 했다. 삭제는 사용자의 탈출구다.
 *
 * <p>앞 단계의 결과는 어차피 버려진다. 밀어내도 잃는 것이 없다.
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
        // 잠글 대상이 없는 destroy 흐름(스택만 남은 경우)을 막으면 정리할 방법이 없다.
        assertThat(lock.preempt(null, "m-destroy")).isTrue();
        assertThat(lock.preempt("c1", null)).isTrue();
    }
}
