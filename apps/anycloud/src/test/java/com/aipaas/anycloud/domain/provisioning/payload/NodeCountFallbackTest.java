package com.aipaas.anycloud.domain.provisioning.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 노드 수 대체값.
 *
 * <p>{@code provisioned ? int : Integer} 는 삼항 안에서 Integer 를 언박싱한다. 스냅샷이 없는 행에서
 * NPE 가 나 VM 목록 전체가 500 이 됐다 — 삭제된 행은 스냅샷이 지워지므로 드문 경우가 아니다.
 */
class NodeCountFallbackTest extends AbstractUnitTest {

    @Test
    void missingSnapshotDoesNotBlowUp() {
        assertThat(NodeCountFallback.workerCount(false, 0, null)).isNull();
    }

    @Test
    void provisionedClusterUsesTheRealCount() {
        assertThat(NodeCountFallback.workerCount(true, 3, 99)).isEqualTo(3);
    }

    @Test
    void beforeProvisioningTheRequestedCountIsShown() {
        // 목록이 빈칸이면 규모를 가늠할 수 없다.
        assertThat(NodeCountFallback.workerCount(false, 0, 2)).isEqualTo(2);
    }

    @Test
    void zeroWorkersIsAnAnswerNotAMissingValue() {
        assertThat(NodeCountFallback.workerCount(true, 0, 5)).isZero();
    }
}
