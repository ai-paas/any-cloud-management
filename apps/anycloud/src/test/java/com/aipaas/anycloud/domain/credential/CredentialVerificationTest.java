package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 가용성 확인은 리전 목록 길이로 판정한다.
 *
 * <p>대부분은 리전 조회가 곧 인증 호출이라 그것으로 충분하다. AWS 만 SDK 에 박힌 정적 목록을
 * 돌려줘 자격증명을 건드리지도 않는다 — 완전히 가짜인 키가 '정상'으로 나왔다.
 */
class CredentialVerificationTest extends AbstractUnitTest {

    @Test
    void awsNowCallsDescribeRegionsSoItCanBeVouchedFor() {
        // 예전엔 Region.regions() 라는 SDK 내장 목록을 써서 가짜 키도 '정상' 이었다.
        assertThat(CredentialVerification.verifiable("AWS")).isTrue();
        assertThat(CredentialVerification.verifiable("aws")).isTrue();
    }

    @Test
    void everyOtherProviderActuallyCallsTheCloudToListRegions() {
        // OpenStack 은 Keystone 토큰을 받고, 나머지는 각자 API 를 친다. 여기서 빼면 멀쩡한
        // 자격증명이 '확인 불가'로 보인다.
        assertThat(CredentialVerification.verifiable("OpenStack")).isTrue();
        assertThat(CredentialVerification.verifiable("Azure")).isTrue();
        assertThat(CredentialVerification.verifiable("Alibaba")).isTrue();
        assertThat(CredentialVerification.verifiable("GCP")).isTrue();
        assertThat(CredentialVerification.verifiable("OCI")).isTrue();
        assertThat(CredentialVerification.verifiable("DigitalOcean")).isTrue();
    }

    @Test
    void nullProviderCannotBeVouchedFor() {
        assertThat(CredentialVerification.verifiable(null)).isFalse();
    }
}
