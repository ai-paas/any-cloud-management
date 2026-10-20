package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 자격증명 오류에 조치 안내 첨부.
 *
 * <p>CSP 원문은 provider 별 표현이 달라 그대로는 조치 대상 판단 불가. 화면이 원문을 접고 안내를
 * 우선 표시할 수 있어야 함.
 */
class CredentialHintTest extends AbstractUnitTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(MAPPER);

    private String hintOf(String field, String reason) {
        var body = handler.handleBusinessException(new CustomException(ErrorCode.RUNTIME_EXCEPTION, field, "v", reason))
                .getBody();
        return body == null ? null : body.getHint();
    }

    @Test
    void permissionFailureTellsToCheckIam() {
        assertThat(hintOf("oci", "404 NotAuthorizedOrNotFound")).contains("권한");
    }

    @Test
    void authenticationFailureTellsToCheckTheKey() {
        assertThat(hintOf("aws", "InvalidAccessKeyId")).contains("거부");
    }

    @Test
    void missingValueTellsToRegisterAgain() {
        assertThat(hintOf("oci", "TF_VAR_fingerprint is required for OCI VM options"))
                .contains("다시 등록");
    }

    @Test
    void upstreamFailureIsNotBlamedOnTheCredential() {
        // 용량 부족을 자격증명 문제로 안내하면 잘못된 지점을 조치.
        assertThat(hintOf("oci", "Out of host capacity")).contains("CSP");
    }

    @Test
    void unclassifiedFailureGetsNoMisleadingHint() {
        // 임의 분류 시 잘못된 안내 전달. 원문 확인만 안내.
        assertThat(hintOf("aws", "something nobody predicted")).contains("원본");
    }
}
