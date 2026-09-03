package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * region id 와 path segment 검증 회귀 방지.
 *
 * <p>Alibaba 와 OCI 는 region 을 host 에 끼워 넣는다. 검증이 빠지면 host 가 통째로 바뀌고,
 * 요청에 실린 access key 와 서명이 공격자 서버로 나간다.
 */
class VmOptionsProviderSsrfTest {

    /** 검증 helper 만 쓰기 위한 최소 구현. */
    private static final class TestProvider extends AbstractVmOptionsProvider {
        @Override
        public SupportedProvisioningProvider getProvider() {
            return SupportedProvisioningProvider.AWS;
        }

        @Override
        public VmOptionProvider describe() {
            return describe(SupportedProvisioningProvider.AWS, false, null);
        }

        String region(String value) {
            return requireValidRegionId(value);
        }

        String segment(String value) {
            return requireValidPathSegment("owner", value);
        }

        String link(String url, String host) {
            return sameHostOrNull(url, host);
        }

        String host(String url, String expected) {
            return requireExpectedHost(url, expected);
        }
    }

    private final TestProvider provider = new TestProvider();

    @ParameterizedTest
    @ValueSource(strings = {"cn-hangzhou", "ap-northeast-2", "ap-seoul-1", "eastus", "us-east-1"})
    @DisplayName("정상 region id 는 통과한다")
    void validRegionIds_pass(String regionId) {
        assertThatCode(() -> provider.region(regionId)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "attacker.com/", // host 를 서브도메인으로 바꿈
                "x@attacker.com/", // userinfo 로 host 자체를 대체
                "internal.svc:8080/", // 내부망 포트 스캔
                "a/../../b", // path traversal
                "a b", // 공백
                "UPPER!", // 허용되지 않는 문자
                "-leading", // 하이픈으로 시작
                "trailing-"
            })
    @DisplayName("host 를 바꿀 수 있는 입력은 거부한다")
    void maliciousRegionIds_rejected(String regionId) {
        assertThatThrownBy(() -> provider.region(regionId)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("blank region 은 통과시킨다 — 호출부가 기본 endpoint 로 분기한다")
    void blankRegion_passesThrough() {
        assertThat(provider.region(null)).isNull();
        assertThat(provider.region("")).isEmpty();
    }

    @Test
    @DisplayName("거부하는 입력이 실제로 host 를 바꾼다는 사실 확인")
    void maliciousRegion_wouldHijackHost() {
        // 검증이 없을 때 어떤 일이 벌어지는지 고정해 둔다. 이 사실이 바뀌면 규칙도 재검토.
        assertThat(URI.create("https://ecs." + "x@attacker.com/" + ".aliyuncs.com/")
                        .getHost())
                .isEqualTo("attacker.com");
        assertThat(URI.create("https://identity." + "attacker.com/" + ".oraclecloud.com")
                        .getHost())
                .isEqualTo("identity.attacker.com");
    }

    @Test
    @DisplayName("조립한 URL 의 host 가 기대값과 다르면 요청 전에 막는다")
    void assembledUrl_hostMustMatch() {
        assertThatCode(() -> provider.host("https://ecs.cn-hangzhou.aliyuncs.com/", "ecs.cn-hangzhou.aliyuncs.com"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.host("https://ecs.attacker.com/", "ecs.cn-hangzhou.aliyuncs.com"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> provider.host("not a url", "ecs.cn-hangzhou.aliyuncs.com"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("path segment 도 같은 규칙으로 막는다")
    void pathSegment_rejectsTraversal() {
        assertThatThrownBy(() -> provider.segment("../../secrets")).isInstanceOf(CustomException.class);
        assertThatCode(() -> provider.segment("debian-cloud")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("응답에서 온 link 는 host 가 같을 때만 따라간다")
    void responseLink_onlyFollowedOnSameHost() {
        String host = "management.azure.com";
        assertThat(provider.link("https://management.azure.com/next?page=2", host))
                .isEqualTo("https://management.azure.com/next?page=2");
        assertThat(provider.link("https://attacker.com/next", host)).isNull();
        assertThat(provider.link("https://management.azure.com.attacker.com/next", host))
                .isNull();
        assertThat(provider.link("not a url", host)).isNull();
        assertThat(provider.link(null, host)).isNull();
    }
}
