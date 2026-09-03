package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 9개 provider 의 spec/image 목록 필터가 공통으로 쓰는 keyword 매칭.
 *
 * <p>여기가 틀리면 사용자가 검색한 것과 다른 목록이 나오는데, provider 별 통합 테스트로는
 * CSP 자격증명이 필요해 검증되지 않는다.
 */
class MatchesKeywordTest {

    private static final class P extends AbstractVmOptionsProvider {
        @Override
        public SupportedProvisioningProvider getProvider() {
            return SupportedProvisioningProvider.AWS;
        }

        @Override
        public VmOptionProvider describe() {
            return describe(SupportedProvisioningProvider.AWS, false, null);
        }

        boolean match(String value, String keyword) {
            return matchesKeyword(value, keyword);
        }
    }

    private final P p = new P();

    @ParameterizedTest
    @CsvSource({
        "ubuntu-22.04-amd64,   ubuntu,  true",
        "ubuntu-22.04-amd64,   UBUNTU,  true",
        "UBUNTU-22.04,         ubuntu,  true",
        "ubuntu-22.04-amd64,   22.04,   true",
        "ubuntu-22.04-amd64,   windows, false",
        "amazon-linux-2023,    linux,   true"
    })
    @DisplayName("대소문자를 구분하지 않고 부분 일치한다")
    void caseInsensitiveContains(String value, String keyword, boolean expected) {
        assertThat(p.match(value, keyword)).isEqualTo(expected);
    }

    @Test
    @DisplayName("keyword 가 비면 필터하지 않는다 (전체 통과)")
    void blankKeyword_passesEverything() {
        assertThat(p.match("anything", null)).isTrue();
        assertThat(p.match("anything", "")).isTrue();
        assertThat(p.match("anything", "   ")).isTrue();
        assertThat(p.match(null, null)).isTrue();
    }

    @Test
    @DisplayName("value 가 null 이면 keyword 가 있을 때 제외한다")
    void nullValue_withKeyword_excluded() {
        assertThat(p.match(null, "ubuntu")).isFalse();
    }
}
