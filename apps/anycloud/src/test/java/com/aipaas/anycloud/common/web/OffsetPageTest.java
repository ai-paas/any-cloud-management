package com.aipaas.anycloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 절단 지점은 한 곳으로 한정.
 *
 * <p>백엔드가 전량을 반환하고 중간 계층이 자르면 계층별 페이지 기준이 분리. 게이트웨이가 기본
 * 20건으로 절단해 화면에서 21번째 이후 항목이 노출되지 않은 사례 발생.
 */
class OffsetPageTest extends AbstractUnitTest {

    private static final List<String> TEN = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

    @Test
    void aPageIsCutFromTheStartOfThatPage() {
        assertThat(OffsetPage.of(TEN, 0, 3).items()).containsExactly("1", "2", "3");
        assertThat(OffsetPage.of(TEN, 1, 3).items()).containsExactly("4", "5", "6");
    }

    @Test
    void theTotalIsAlwaysTheWholeCount() {
        // 화면이 페이지 수를 계산하려면 이번 페이지 길이가 아니라 전체가 필요하다.
        assertThat(OffsetPage.of(TEN, 1, 3).total()).isEqualTo(10);
    }

    @Test
    void theLastPageIsShorter() {
        assertThat(OffsetPage.of(TEN, 3, 3).items()).containsExactly("10");
    }

    @Test
    void askingBeyondTheEndGivesAnEmptyPage() {
        // 마지막 페이지에서 항목 삭제 시 해당 페이지 소멸. 500 이 아닌 빈 페이지 반환 필요.
        OffsetPage<String> page = OffsetPage.of(TEN, 9, 3);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(10);
    }

    @Test
    void withoutASizeEverythingComesBack() {
        // 페이징 미사용 호출자 잔존. 미지정 시 전체 반환.
        assertThat(OffsetPage.of(TEN, null, null).items()).hasSize(10);
        assertThat(OffsetPage.of(TEN, 2, null).items()).hasSize(10);
    }

    @Test
    void anEmptyListIsNotAnError() {
        assertThat(OffsetPage.of(List.<String>of(), 0, 10).total()).isZero();
        assertThat(OffsetPage.of(null, 0, 10).items()).isEmpty();
    }
}
