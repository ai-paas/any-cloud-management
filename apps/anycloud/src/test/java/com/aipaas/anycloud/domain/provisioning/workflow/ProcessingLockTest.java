package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * 처리 중 소유권.
 *
 * <p>{@code last_processed_workflow_message_id} 는 처리가 <b>끝난 뒤</b> 기록된다. 재전달은 처리
 * <b>도중</b> 오므로 그 가드를 그대로 통과한다. AMQP delivery ack 타임아웃에 걸려 메시지가
 * 재전달되면서 같은 노드에 부트스트랩이 두 번 붙는 것을 실제로 관측했다.
 */
class ProcessingLockTest extends AbstractUnitTest {

    @Mock
    VmClusterRepository vmClusterRepository;

    private ProcessingLock lock;

    @BeforeEach
    void setUp() {
        lock = new ProcessingLock(vmClusterRepository, Duration.ofMinutes(45));
    }

    @Test
    void acquiresWhenNobodyHoldsIt() {
        when(vmClusterRepository.acquireProcessing(eq("c1"), eq("m1"), any(), any()))
                .thenReturn(1);

        assertThat(lock.acquire("c1", "m1")).isTrue();
    }

    @Test
    void refusesWhenAnotherMessageIsInFlight() {
        // 재전달을 이 지점에서 차단. 미차단 시 동일 노드에 부트스트랩 중복 실행.
        when(vmClusterRepository.acquireProcessing(eq("c1"), eq("m2"), any(), any()))
                .thenReturn(0);

        assertThat(lock.acquire("c1", "m2")).isFalse();
    }

    @Test
    void reclaimsOwnershipOlderThanTheStaleWindow() {
        // 워커 종료 시 락 해제 주체 부재. 회수 기준을 쿼리에 전달.
        when(vmClusterRepository.acquireProcessing(any(), any(), any(), any())).thenReturn(1);

        lock.acquire("c1", "m1");

        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(vmClusterRepository).acquireProcessing(any(), any(), any(), staleBefore.capture());
        assertThat(staleBefore.getValue()).isBefore(LocalDateTime.now().minusMinutes(44));
    }

    @Test
    void releasesOnlyItsOwnLock() {
        lock.release("c1", "m1");

        verify(vmClusterRepository).releaseProcessing("c1", "m1");
    }

    @Test
    void skipsWhenThereIsNoClusterToLock() {
        // vmClusterId 가 없는 메시지(일부 destroy 흐름)는 잠금 대상 부재.
        assertThat(lock.acquire(null, "m1")).isTrue();
        assertThat(lock.acquire("c1", null)).isTrue();

        verify(vmClusterRepository, never()).acquireProcessing(any(), any(), any(), any());
    }

    @Test
    void releaseIsANoOpWithoutIds() {
        lock.release(null, "m1");
        lock.release("c1", null);

        verify(vmClusterRepository, never()).releaseProcessing(any(), any());
    }

    @Test
    void staleWindowOutlivesTheAmqpAckTimeout() {
        // 회수 기준이 ack 타임아웃(30분)보다 짧으면 실행 중인 워커의 락을 재전달이 탈취해
        // 중복 실행 발생.
        assertThat(ProcessingLock.DEFAULT_STALE_AFTER).isGreaterThan(Duration.ofMinutes(30));
    }
}
