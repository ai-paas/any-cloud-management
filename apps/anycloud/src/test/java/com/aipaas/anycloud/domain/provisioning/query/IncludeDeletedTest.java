package com.aipaas.anycloud.domain.provisioning.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 삭제된 것을 "함께" 볼지, "만" 볼지.
 *
 * <p>지금은 status=DELETED 로 토글해서 삭제된 것만 보였다. 살아 있는 것이 사라져 두 번 조회해야
 * 한다. 사용자가 원하는 건 "삭제된 것도 같이 보기" 다.
 */
class IncludeDeletedTest extends AbstractUnitTest {

    @Test
    void defaultHidesDeleted() {
        // 삭제 이력이 목록에 쌓이면 살아 있는 것을 찾기 어렵다.
        assertThat(VmClusterListFilter.hidesDeleted(null, false)).isTrue();
    }

    @Test
    void includeDeletedShowsEverything() {
        // 토글은 "함께 보기" 다. 살아 있는 것이 사라지면 안 된다.
        assertThat(VmClusterListFilter.hidesDeleted(null, true)).isFalse();
    }

    @Test
    void explicitStatusIsNotOverriddenByTheToggle() {
        // status=FAILED 는 FAILED 만 보겠다는 뜻이다. 토글이 이를 뒤집으면 필터가 거짓말이 된다.
        assertThat(VmClusterListFilter.hidesDeleted(VmClusterStatus.FAILED, true))
                .isFalse();
        assertThat(VmClusterListFilter.hidesDeleted(VmClusterStatus.FAILED, false))
                .isFalse();
    }

    @Test
    void explicitDeletedStatusStillWorks() {
        // 삭제된 것만 보고 싶을 때가 있다. status=DELETED 는 그대로 둔다.
        assertThat(VmClusterListFilter.hidesDeleted(VmClusterStatus.DELETED, false))
                .isFalse();
    }
}
