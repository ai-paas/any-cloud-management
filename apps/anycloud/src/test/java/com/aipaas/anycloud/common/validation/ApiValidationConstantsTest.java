package com.aipaas.anycloud.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 정규식 회귀 방지. 운영 중 누군가 패턴을 의도치 않게 풀어주면(예: 대문자 허용) 즉시 빨갛게 뜨도록.
 */
class ApiValidationConstantsTest extends AbstractUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"demo-aws-01", "a", "x1", "node1", "cluster-1-2-3"})
    void k8sName_acceptsValid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.K8S_NAME_PATTERN, input))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-bad", "bad-", "BadCase", "with_underscore", "with.dot", "", " ", "한글"})
    void k8sName_rejectsInvalid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.K8S_NAME_PATTERN, input))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "AWS", "gcp", "openstack", "digital-ocean", "p_x"})
    void provider_acceptsValid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.PROVIDER_PATTERN, input))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-aws", "1aws", "", " aws", "aws/!"})
    void provider_rejectsInvalid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.PROVIDER_PATTERN, input))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ap-northeast-2", "us-east-1", "cn-shanghai", "eu-west-1"})
    void region_acceptsValid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.REGION_PATTERN, input))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ap northeast 2", "us_east_1", "한국", ""})
    void region_rejectsInvalid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.REGION_PATTERN, input))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROVISIONING", "READY", "BLOCKED", "FAILED", "DELETED"})
    void status_acceptsValid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.STATUS_PATTERN, input))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ready", "Ready", "Re_ady", "READY!"})
    void status_rejectsInvalid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.STATUS_PATTERN, input))
                .isFalse();
    }

    @Test
    void k8sNameMax_is63() {
        assertThat(ApiValidationConstants.K8S_NAME_MAX).isEqualTo(63);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "kube-system", "web", "-", "_all", "ns1"})
    void namespace_acceptsValidAndSentinels(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.NAMESPACE_PATTERN, input))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-bad", "bad-", "Default", "ns_underscore", "", " ", "한글"})
    void namespace_rejectsInvalid(String input) {
        assertThat(Pattern.matches(ApiValidationConstants.NAMESPACE_PATTERN, input))
                .isFalse();
    }
}
