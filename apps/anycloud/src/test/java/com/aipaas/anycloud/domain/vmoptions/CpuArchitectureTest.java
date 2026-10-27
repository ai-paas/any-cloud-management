package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 조회 API 가 요청값을 {@code x86_64} 로 바꿔 전달해 {@code amd64} 를 보고하는 IBM 이미지가
 * 한 건도 반환되지 않았다. 표기가 달라도 같은 아키텍처면 일치로 판정해야 한다.
 */
class CpuArchitectureTest extends AbstractUnitTest {

    @Test
    void differentNotations_ofSameArchitecture_match() {
        assertThat(CpuArchitecture.matches("amd64", "x86_64")).isTrue();
        assertThat(CpuArchitecture.matches("x86_64", "amd64")).isTrue();
        assertThat(CpuArchitecture.matches("X86", "amd64")).isTrue();
        assertThat(CpuArchitecture.matches("aarch64", "arm64")).isTrue();
    }

    @Test
    void differentArchitectures_doNotMatch() {
        assertThat(CpuArchitecture.matches("amd64", "s390x")).isFalse();
        assertThat(CpuArchitecture.matches("x86_64", "arm64")).isFalse();
    }

    @Test
    void blankRequest_meansNoFiltering() {
        assertThat(CpuArchitecture.matches(null, "s390x")).isTrue();
        assertThat(CpuArchitecture.matches("", "s390x")).isTrue();
    }

    @Test
    void unknownNotation_passesRatherThanFiltersOut() {
        // 새 CSP 나 새 아키텍처가 들어왔을 때 정상 조합을 차단하지 않는다.
        assertThat(CpuArchitecture.matches("amd64", "sparc")).isTrue();
        assertThat(CpuArchitecture.matches("sparc", "amd64")).isTrue();
    }

    @Test
    void canonical_mapsKnownNotations() {
        assertThat(CpuArchitecture.canonical("x86-64")).isEqualTo("amd64");
        assertThat(CpuArchitecture.canonical("ARM")).isEqualTo("arm64");
        assertThat(CpuArchitecture.canonical("s390x")).isEqualTo("s390x");
        assertThat(CpuArchitecture.canonical("sparc")).isNull();
        assertThat(CpuArchitecture.canonical(null)).isNull();
    }
}
