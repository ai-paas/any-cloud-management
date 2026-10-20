package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 삭제 전 CSP 실제 상태 조회.
 *
 * <p>상태 변경 경로는 화면 외에도 존재. CSP 콘솔에서 VM 을 직접 삭제하거나 중지하면 기록과
 * 불일치하며, 미확인 상태로 destroy 실행.
 *
 * <p>refresh 실패가 삭제를 차단하면 CSP 장애 상황에서 정리 수단 부재.
 */
class DestroyPrecheckTest extends AbstractUnitTest {

    @Test
    void refreshSucceededAndResourcesRemain() {
        DestroyPrecheck result = DestroyPrecheck.refreshed(3);
    }

    @Test
    void nothingVisibleStillRunsDestroy() {
        /*
         * outputs 가 비어 있어도 자원이 없다는 뜻은 아님. up 이 중간에 실패하면 보안 그룹과
         * 네트워크는 생성된 상태에서 outputs 는 0. 이때 destroy 를 생략하면 해당 자원이 잔존해
         * 공유 테넌트 쿼터 소모. 리허설에서 보안 그룹 6개 잔존 확인.
         *
         * destroy 는 멱등. 대상이 없으면 즉시 종료, 있으면 삭제. 생략 사유 없음.
         */
        DestroyPrecheck result = DestroyPrecheck.refreshed(0);

        assertThat(result.note()).contains("그대로 삭제를 진행");
    }

    @Test
    void whatWasSeenIsStillRecorded() {
        // 생략하지 않더라도 CSP 조회 결과는 기록 필요. 콘솔 직접 삭제 사례의 사후 설명에 사용.
        assertThat(DestroyPrecheck.refreshed(3).note()).contains("3");
    }

    @Test
    void aFailedRefreshStillLetsDeletionProceed() {
        // CSP 무응답을 이유로 삭제를 차단하면 정리 수단 부재.
        DestroyPrecheck result = DestroyPrecheck.failed("timeout");

        assertThat(result.note()).contains("timeout");
    }

    @Test
    void skippedPrecheckBehavesLikeBefore() {
        // 기능 비활성 상태에서도 삭제는 정상 동작 필요.
        DestroyPrecheck result = DestroyPrecheck.skipped();
    }

    @Test
    void theNoteSaysWhatWasFound() {
        // 작업 이력 기록 문구. 실패 표기만으로는 사유 확인 불가.
        assertThat(DestroyPrecheck.refreshed(0).note()).contains("그대로 삭제를 진행");
        assertThat(DestroyPrecheck.refreshed(2).note()).contains("2");
    }
}
