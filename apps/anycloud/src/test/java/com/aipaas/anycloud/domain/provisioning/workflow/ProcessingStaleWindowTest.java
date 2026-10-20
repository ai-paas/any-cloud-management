package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 락 회수 창은 실제 작업 소요 시간보다 커야 함.
 *
 * <p>작으면 실행 중인 작업의 락을 다른 프로세스가 탈취해 동일 노드에 부트스트랩이 중복 실행.
 * BOOTSTRAP 최악 예산이 170분이라 45분으로는 부족.
 */
class ProcessingStaleWindowTest extends AbstractUnitTest {

    /** BOOTSTRAP 단계의 최악 합계. 상수 변경 시 이 값도 재계산 필요. */
    private static final Duration WORST_CASE_BOOTSTRAP = Duration.ofMinutes(170);

    @Test
    void staleWindowOutlastsTheWorstCaseStep() {
        assertThat(ProcessingLock.DEFAULT_STALE_AFTER).isGreaterThan(WORST_CASE_BOOTSTRAP);
    }

    @Test
    void staleWindowIsNotUnbounded() {
        // 크래시로 잔존한 락이 영구 차단하면 DB 직접 조작 필요.
        assertThat(ProcessingLock.DEFAULT_STALE_AFTER).isLessThan(Duration.ofHours(12));
    }
}
