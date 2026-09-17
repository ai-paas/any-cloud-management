package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 지우기 전에 CSP 의 실제 상태를 본다.
 *
 * <p>화면에서만 상태를 바꾸는 것이 아니다. 누가 CSP 콘솔에서 직접 VM 을 지우거나 끄면 우리 기록과
 * 어긋나는데, 지금은 그걸 모른 채 destroy 를 돌린다.
 *
 * <p>refresh 실패가 삭제를 막으면 안 된다 — CSP 장애 때 정리할 방법이 없어진다.
 */
class DestroyPrecheckTest extends AbstractUnitTest {

    @Test
    void refreshSucceededAndResourcesRemain() {
        DestroyPrecheck result = DestroyPrecheck.refreshed(3);
    }

    @Test
    void nothingVisibleStillRunsDestroy() {
        /*
         * outputs 가 비었다고 자원이 없는 것이 아니다. up 이 중간에 실패하면 보안 그룹과 네트워크는
         * 만들어졌는데 outputs 는 0 이다. 그때 destroy 를 건너뛰면 그 자원들이 그대로 남아 공유
         * 테넌트의 쿼터를 문다 — 리허설에서 보안 그룹 6개가 그렇게 남았다.
         *
         * destroy 는 멱등이다. 없으면 금방 끝나고, 있으면 지운다. 건너뛸 이유가 없다.
         */
        DestroyPrecheck result = DestroyPrecheck.refreshed(0);

        assertThat(result.note()).contains("그대로 삭제를 진행");
    }

    @Test
    void whatWasSeenIsStillRecorded() {
        // 건너뛰지 않더라도 CSP 에서 무엇을 봤는지는 남겨야 한다 — 콘솔에서 직접 지운 경우를
        // 나중에 설명할 수 있어야 한다.
        assertThat(DestroyPrecheck.refreshed(3).note()).contains("3");
    }

    @Test
    void aFailedRefreshStillLetsDeletionProceed() {
        // CSP 가 응답하지 않는다고 삭제를 막으면 정리할 방법이 없어진다.
        DestroyPrecheck result = DestroyPrecheck.failed("timeout");

        assertThat(result.note()).contains("timeout");
    }

    @Test
    void skippedPrecheckBehavesLikeBefore() {
        // 기능을 꺼도 삭제는 그대로 동작해야 한다.
        DestroyPrecheck result = DestroyPrecheck.skipped();
    }

    @Test
    void theNoteSaysWhatWasFound() {
        // 작업 이력에 남는 문장이다. '실패' 만 남으면 왜인지 알 수 없다.
        assertThat(DestroyPrecheck.refreshed(0).note()).contains("그대로 삭제를 진행");
        assertThat(DestroyPrecheck.refreshed(2).note()).contains("2");
    }
}
