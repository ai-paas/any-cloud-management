package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 자격증명 오류에 할 일을 붙인다.
 *
 * <p>CSP 가 주는 원문은 provider 마다 표현이 달라 그대로 보면 무엇을 고쳐야 하는지 알 수 없다.
 * 화면이 원문을 접어두고 안내부터 보여줄 수 있어야 한다.
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
        // 용량 부족을 자격증명 문제로 안내하면 엉뚱한 곳을 고치게 된다.
        assertThat(hintOf("oci", "Out of host capacity")).contains("CSP");
    }

    @Test
    void unclassifiedFailureGetsNoMisleadingHint() {
        // 억지로 분류하면 엉뚱한 안내가 나간다. 원본을 보라고만 한다.
        assertThat(hintOf("aws", "something nobody predicted")).contains("원본");
    }
}
