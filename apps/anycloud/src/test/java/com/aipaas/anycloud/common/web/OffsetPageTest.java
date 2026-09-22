package com.aipaas.anycloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 자르는 곳은 한 곳이어야 한다.
 *
 * <p>백엔드가 전량을 주고 중간 계층이 자르면 각 층이 서로 다른 페이지를 가정한다 — 실제로
 * 게이트웨이가 기본 20건으로 잘라 화면이 21번째부터를 영영 보지 못했다.
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
        // 마지막 페이지에서 지우면 그 페이지가 사라진다. 500 이 아니라 빈 페이지여야 한다.
        OffsetPage<String> page = OffsetPage.of(TEN, 9, 3);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(10);
    }

    @Test
    void withoutASizeEverythingComesBack() {
        // 페이징을 안 쓰는 호출자가 남아 있다. 비우면 전부 준다.
        assertThat(OffsetPage.of(TEN, null, null).items()).hasSize(10);
        assertThat(OffsetPage.of(TEN, 2, null).items()).hasSize(10);
    }

    @Test
    void anEmptyListIsNotAnError() {
        assertThat(OffsetPage.of(List.<String>of(), 0, 10).total()).isZero();
        assertThat(OffsetPage.of(null, 0, 10).items()).isEmpty();
    }
}
