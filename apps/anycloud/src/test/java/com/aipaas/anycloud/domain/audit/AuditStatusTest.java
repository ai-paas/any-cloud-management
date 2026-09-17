package com.aipaas.anycloud.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 감사 기록의 상태 코드는 클라이언트가 받은 것과 같아야 한다.
 *
 * <p>없는 클러스터 조회가 500 으로 남으면 평범한 404 가 장애로 보인다.
 */
class AuditStatusTest extends AbstractUnitTest {

    @Test
    void successIsTwoHundred() {
        assertThat(AuditStatus.of(null)).isEqualTo(200);
    }

    @Test
    void notFoundStaysNotFound() {
        assertThat(AuditStatus.of(new ClusterNotFoundException("demo"))).isEqualTo(404);
    }

    @Test
    void usesTheErrorCodeStatus() {
        assertThat(AuditStatus.of(new CustomException(ErrorCode.CLUSTER_NOT_FOUND)))
                .isEqualTo(404);
    }

    @Test
    void unknownFailuresStayFiveHundred() {
        assertThat(AuditStatus.of(new IllegalStateException("boom"))).isEqualTo(500);
    }
}
