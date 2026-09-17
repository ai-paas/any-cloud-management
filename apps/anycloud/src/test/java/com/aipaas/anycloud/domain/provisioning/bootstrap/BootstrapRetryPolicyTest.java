package com.aipaas.anycloud.domain.provisioning.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 갓 만들어진 노드는 바로 SSH 를 받지 않는다.
 *
 * <p>7개를 동시에 띄웠을 때 한 노드가 늦게 부팅됐는데, 재시도 3회에 고정 10초라 20초 만에 포기하고
 * 클러스터가 통째로 실패했다 — "Failed during node preparation after 3 attempts". cloud-init
 * 타임아웃은 20분인데 그 예산을 써보지도 못했다.
 */
class BootstrapRetryPolicyTest extends AbstractUnitTest {

    private Duration totalWait(int attempts) {
        Duration sum = Duration.ZERO;
        for (int attempt = 1; attempt < attempts; attempt++) {
            sum = sum.plus(BootstrapRetryPolicy.delayBeforeRetry(attempt));
        }
        return sum;
    }

    @Test
    void nodePreparationWaitsLongEnoughForACloudInitBoot() {
        // OpenStack 노드는 부팅과 cloud-init 에 보통 1~3분 걸린다. 동시에 여럿 띄우면 더 늦다.
        assertThat(totalWait(BootstrapRetryPolicy.PREPARATION_ATTEMPTS))
                .as("노드 부팅을 기다릴 예산")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void theFirstRetryIsQuick() {
        // 대부분은 금방 준비된다. 첫 재시도까지 30초씩 기다리면 정상 경로가 느려진다.
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(1)).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theDelayGrowsSoWeStopHammeringAHostThatIsNotUp() {
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(3)).isGreaterThan(BootstrapRetryPolicy.delayBeforeRetry(1));
    }

    @Test
    void theDelayIsCappedSoTheLastAttemptIsNotHoursAway() {
        // 상한이 없으면 지수 증가가 단계 예산을 통째로 먹는다.
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(100)).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theWholeBudgetStaysWithinTheCloudInitTimeout() {
        // 재시도 예산이 cloud-init 타임아웃을 넘으면 단계가 두 배로 늘어진다.
        assertThat(totalWait(BootstrapRetryPolicy.PREPARATION_ATTEMPTS)).isLessThan(Duration.ofMinutes(20));
    }

    @Test
    void stepsThatRunAfterNodesAreUpDoNotNeedTheSameBudget() {
        // master init 은 노드가 이미 준비된 뒤다. 거기까지 늘리면 실패를 늦게 안다.
        assertThat(BootstrapRetryPolicy.MASTER_INIT_ATTEMPTS).isLessThan(BootstrapRetryPolicy.PREPARATION_ATTEMPTS);
    }
}
