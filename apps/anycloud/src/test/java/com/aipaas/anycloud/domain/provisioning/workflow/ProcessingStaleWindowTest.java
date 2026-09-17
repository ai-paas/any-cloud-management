package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 락을 회수하는 창은 작업이 실제로 걸리는 시간보다 길어야 한다.
 *
 * <p>짧으면 아직 도는 작업의 락을 남이 뺏어가 같은 노드에 부트스트랩이 두 번 붙는다.
 * BOOTSTRAP 최악 예산이 170분이라 45분으로는 모자랐다.
 */
class ProcessingStaleWindowTest extends AbstractUnitTest {

    /** BOOTSTRAP 단계들의 최악 합계. 상수를 바꾸면 이 값도 다시 계산해야 한다. */
    private static final Duration WORST_CASE_BOOTSTRAP = Duration.ofMinutes(170);

    @Test
    void staleWindowOutlastsTheWorstCaseStep() {
        assertThat(ProcessingLock.DEFAULT_STALE_AFTER).isGreaterThan(WORST_CASE_BOOTSTRAP);
    }

    @Test
    void staleWindowIsNotUnbounded() {
        // 크래시로 남은 락이 영원히 막으면 사람이 DB 를 건드려야 한다.
        assertThat(ProcessingLock.DEFAULT_STALE_AFTER).isLessThan(Duration.ofHours(12));
    }
}
