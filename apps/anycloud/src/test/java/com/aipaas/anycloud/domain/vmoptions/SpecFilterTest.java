package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.vmoptions.api.SpecFilter;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/** 조건을 비우면 control-plane 이 뜨는 최소선을 쓴다. */
class SpecFilterTest extends AbstractUnitTest {

    @Test
    void emptyValuesFallBackToTheDefault() {
        SpecFilter filter = SpecFilter.of(null, null, null);

        assertThat(filter.minVcpu()).isEqualTo(SpecFilter.DEFAULT.minVcpu());
        assertThat(filter.minMemoryGb()).isEqualTo(SpecFilter.DEFAULT.minMemoryGb());
        assertThat(filter.gpu()).isFalse();
    }

    @Test
    void nonsenseValuesFallBackToo() {
        // 0 이나 음수로 물으면 아무 인스턴스나 잡혀 클러스터가 안 선다.
        assertThat(SpecFilter.of(0, -1.0, null).minVcpu()).isEqualTo(SpecFilter.DEFAULT.minVcpu());
    }

    @Test
    void givenValuesAreKept() {
        SpecFilter filter = SpecFilter.of(8, 32.0, true);

        assertThat(filter.minVcpu()).isEqualTo(8);
        assertThat(filter.minMemoryGb()).isEqualTo(32.0);
        assertThat(filter.gpu()).isTrue();
    }
}
