package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 자격증명 실패를 사용자가 할 일 기준으로 나눈다.
 *
 * <p>지금은 "값 없음", "키가 틀림", "권한 없음" 이 모두 같은 메시지로 나와 무엇을 고쳐야 하는지
 * 알 수 없다. 셋은 고치는 곳이 전혀 다르다 — 재등록, 키 재발급, IAM 정책.
 */
class CredentialFailureKindTest extends AbstractUnitTest {

    @ParameterizedTest
    @CsvSource({
        // 값 누락 — preflight 가 잡는다
        "'TF_VAR_fingerprint is required for OCI VM options', MISSING_VALUE",
        "'Missing required credential keys: ARM_CLIENT_SECRET', MISSING_VALUE",
        // 값은 있는데 읽히지 않는다 — 형식이 깨졌거나 잘못 붙여 넣었다. 고칠 곳은 재등록이다.
        "'Failed to read OCI private key:', MISSING_VALUE",
        "'Failed to sign OCI VM options request', MISSING_VALUE",
        "'could not parse private key', MISSING_VALUE",
        // 인증 거부 — 키가 틀렸거나 만료
        "'InvalidAccessKeyId: The AWS Access Key Id does not exist', AUTHENTICATION_FAILED",
        "'SignatureDoesNotMatch', AUTHENTICATION_FAILED",
        "'401 Unauthorized', AUTHENTICATION_FAILED",
        "'NotAuthenticated', AUTHENTICATION_FAILED",
        "'invalid_grant', AUTHENTICATION_FAILED",
        // 권한 부족 — 인증은 됐는데 못 하게 막힘
        "'AccessDenied: User is not authorized to perform ec2:RunInstances', PERMISSION_DENIED",
        "'PERMISSION_DENIED', PERMISSION_DENIED",
        "'AuthorizationFailed', PERMISSION_DENIED",
        "'403 Forbidden', PERMISSION_DENIED",
        // CSP 쪽 사정 — 자격증명 문제가 아니다
        "'ServiceUnavailable', UPSTREAM_UNAVAILABLE",
        "'Out of host capacity', UPSTREAM_UNAVAILABLE",
        "'ThrottlingException', UPSTREAM_UNAVAILABLE",
    })
    void classifies(String raw, String expected) {
        assertThat(CredentialFailureKind.from(raw)).hasToString(expected);
    }

    @Test
    void unknownTextStaysUnknown() {
        // 억지로 분류하면 엉뚱한 안내가 나간다.
        assertThat(CredentialFailureKind.from("something nobody predicted")).isEqualTo(CredentialFailureKind.UNKNOWN);
        assertThat(CredentialFailureKind.from(null)).isEqualTo(CredentialFailureKind.UNKNOWN);
    }

    @Test
    void permissionBeatsAuthenticationWhenBothAppear() {
        // OCI 의 NotAuthorizedOrNotFound 처럼 한 문자열에 둘 다 읽히는 경우가 있다.
        // 인증이 됐으니 권한 쪽을 보게 하는 편이 실제로 맞다.
        assertThat(CredentialFailureKind.from("NotAuthorizedOrNotFound"))
                .isEqualTo(CredentialFailureKind.PERMISSION_DENIED);
    }

    @Test
    void everyKindHasActionableGuidance() {
        // 분류만 하고 할 일을 안 알려주면 나눈 의미가 없다.
        for (CredentialFailureKind kind : CredentialFailureKind.values()) {
            assertThat(kind.hint()).as("%s 의 안내가 비었다", kind).isNotBlank();
        }
    }
}
