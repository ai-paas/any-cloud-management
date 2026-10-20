package com.aipaas.anycloud.domain.provisioning.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 생성 직후 노드는 SSH 를 즉시 수용하지 않음.
 *
 * <p>7개 동시 생성 시 한 노드의 부팅 지연 상황에서 재시도 3회, 고정 10초 설정이 20초 만에
 * 중단돼 클러스터 전체 실패. "Failed during node preparation after 3 attempts".
 * cloud-init 타임아웃 20분을 소진하기 전에 종료.
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
        // 대부분 노드는 빠르게 준비 완료. 첫 재시도까지 30초 대기 시 정상 경로 지연.
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(1)).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theDelayGrowsSoWeStopHammeringAHostThatIsNotUp() {
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(3)).isGreaterThan(BootstrapRetryPolicy.delayBeforeRetry(1));
    }

    @Test
    void theDelayIsCappedSoTheLastAttemptIsNotHoursAway() {
        // 상한 부재 시 지수 증가가 단계 예산 전체를 소진.
        assertThat(BootstrapRetryPolicy.delayBeforeRetry(100)).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theWholeBudgetStaysWithinTheCloudInitTimeout() {
        // 재시도 예산이 cloud-init 타임아웃을 초과하면 단계 소요 시간 2배 증가.
        assertThat(totalWait(BootstrapRetryPolicy.PREPARATION_ATTEMPTS)).isLessThan(Duration.ofMinutes(20));
    }

    @Test
    void stepsThatRunAfterNodesAreUpDoNotNeedTheSameBudget() {
        // master init 은 노드가 이미 준비된 뒤다. 거기까지 늘리면 실패를 늦게 안다.
        assertThat(BootstrapRetryPolicy.MASTER_INIT_ATTEMPTS).isLessThan(BootstrapRetryPolicy.PREPARATION_ATTEMPTS);
    }
}
