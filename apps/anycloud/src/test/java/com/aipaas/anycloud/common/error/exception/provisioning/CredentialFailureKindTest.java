package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 자격증명 실패를 사용자 조치 기준으로 분류.
 *
 * <p>값 없음, 키 불일치, 권한 없음이 동일 메시지로 전달돼 조치 대상 판단 불가. 세 경우의 조치
 * 지점은 각각 재등록, 키 재발급, IAM 정책.
 */
class CredentialFailureKindTest extends AbstractUnitTest {

    @ParameterizedTest
    @CsvSource({
        // 값 누락. preflight 에서 검출
        "'TF_VAR_fingerprint is required for OCI VM options', MISSING_VALUE",
        "'Missing required credential keys: ARM_CLIENT_SECRET', MISSING_VALUE",
        // 값은 존재하나 파싱 실패. 형식 손상 또는 붙여넣기 오류이며 조치는 재등록.
        "'Failed to read OCI private key:', MISSING_VALUE",
        "'Failed to sign OCI VM options request', MISSING_VALUE",
        "'could not parse private key', MISSING_VALUE",
        // 인증 거부: 키가 틀렸거나 만료
        "'InvalidAccessKeyId: The AWS Access Key Id does not exist', AUTHENTICATION_FAILED",
        "'SignatureDoesNotMatch', AUTHENTICATION_FAILED",
        "'401 Unauthorized', AUTHENTICATION_FAILED",
        "'NotAuthenticated', AUTHENTICATION_FAILED",
        "'invalid_grant', AUTHENTICATION_FAILED",
        // 권한 부족: 인증은 됐는데 못 하게 막힘
        "'AccessDenied: User is not authorized to perform ec2:RunInstances', PERMISSION_DENIED",
        "'PERMISSION_DENIED', PERMISSION_DENIED",
        "'AuthorizationFailed', PERMISSION_DENIED",
        "'403 Forbidden', PERMISSION_DENIED",
        // CSP 측 사유. 자격증명 문제 아님
        "'ServiceUnavailable', UPSTREAM_UNAVAILABLE",
        "'Out of host capacity', UPSTREAM_UNAVAILABLE",
        "'ThrottlingException', UPSTREAM_UNAVAILABLE",
    })
    void classifies(String raw, String expected) {
        assertThat(CredentialFailureKind.from(raw)).hasToString(expected);
    }

    @Test
    void unknownTextStaysUnknown() {
        // 임의 분류 시 잘못된 안내 전달.
        assertThat(CredentialFailureKind.from("something nobody predicted")).isEqualTo(CredentialFailureKind.UNKNOWN);
        assertThat(CredentialFailureKind.from(null)).isEqualTo(CredentialFailureKind.UNKNOWN);
    }

    @Test
    void permissionBeatsAuthenticationWhenBothAppear() {
        // OCI 의 NotAuthorizedOrNotFound 처럼 한 문자열이 양쪽으로 해석되는 경우 존재.
        // 인증은 완료된 상태이므로 권한 쪽으로 안내.
        assertThat(CredentialFailureKind.from("NotAuthorizedOrNotFound"))
                .isEqualTo(CredentialFailureKind.PERMISSION_DENIED);
    }

    @Test
    void everyKindHasActionableGuidance() {
        // 분류만 제공하고 조치를 안내하지 않으면 분류 목적 미달.
        for (CredentialFailureKind kind : CredentialFailureKind.values()) {
            assertThat(kind.hint()).as("%s 의 안내가 비었다", kind).isNotBlank();
        }
    }
}
