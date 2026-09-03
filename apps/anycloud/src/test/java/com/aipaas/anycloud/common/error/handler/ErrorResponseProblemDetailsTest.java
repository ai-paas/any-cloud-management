package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * UX #7 — ErrorResponse RFC 9457 호환 type URI 회귀 보호.
 *
 * <p>
 * 기존 응답 구조 (code/message/status/errors) 는 그대로 유지하고 type URI 만 추가
 * (additive — 기존 클라이언트 비파괴).
 */
class ErrorResponseProblemDetailsTest extends AbstractUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void invalidInputValue_typeUriIsKebabCase() {
        ErrorResponse resp = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE);

        assertThat(resp.getType()).isEqualTo("https://anycloud.local/problems/invalid-input-value");
        assertThat(resp.getCode()).isEqualTo("INVALID_INPUT_VALUE");
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    void granularProvisioningCode_hasOwnTypeUri() {
        ErrorResponse resp = ErrorResponse.of(ErrorCode.PROVISIONING_CONFIG_MISSING_KEY);

        assertThat(resp.getType()).isEqualTo("https://anycloud.local/problems/provisioning-config-missing-key");
        assertThat(resp.getCode()).isEqualTo("PROVISIONING_CONFIG_MISSING_KEY");
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    void clusterBootstrapFailed_distinctFromInvalidInput() {
        ErrorResponse bootstrap = ErrorResponse.of(ErrorCode.CLUSTER_BOOTSTRAP_FAILED);
        ErrorResponse invalid = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE);

        assertThat(bootstrap.getType()).isNotEqualTo(invalid.getType());
        assertThat(bootstrap.getStatus()).isEqualTo(500);
        assertThat(invalid.getStatus()).isEqualTo(400);
    }

    @Test
    void responseSerializesAllFieldsIncludingType() throws Exception {
        // 클라이언트는 JSON 응답에서 type 필드를 읽어 카테고리 분기 가능해야 함.
        ErrorResponse resp = ErrorResponse.of(ErrorCode.PROVISIONING_PROVIDER_UNSUPPORTED, "test message");

        String json = mapper.writeValueAsString(resp);

        assertThat(json).contains("\"type\":\"https://anycloud.local/problems/provisioning-provider-unsupported\"");
        assertThat(json).contains("\"code\":\"PROVISIONING_PROVIDER_UNSUPPORTED\"");
        assertThat(json).contains("\"status\":400");
        assertThat(json).contains("\"message\":\"test message\"");
    }

    @Test
    void newErrorCodes_haveExpectedStatuses() {
        // UX #7 신규 코드들의 HTTP status 매핑 회귀 보호.
        assertThat(ErrorCode.PROVISIONING_PROVIDER_UNSUPPORTED.getStatus()).isEqualTo(400);
        assertThat(ErrorCode.PROVISIONING_CONFIG_MISSING_KEY.getStatus()).isEqualTo(400);
        assertThat(ErrorCode.PROVISIONING_CONFIG_INVALID_VALUE.getStatus()).isEqualTo(400);
        assertThat(ErrorCode.CLUSTER_BOOTSTRAP_FAILED.getStatus()).isEqualTo(500);
        assertThat(ErrorCode.CLUSTER_PULUMI_FAILED.getStatus()).isEqualTo(500);
    }

    // ===== ofSummarized + detail/hint =====

    @Test
    void ofSummarized_shortSingleLine_noDetail() {
        ErrorResponse resp = ErrorResponse.ofSummarized(ErrorCode.STATE_CONFLICT, "짧은 한 줄 에러");

        assertThat(resp.getMessage()).isEqualTo("짧은 한 줄 에러");
        assertThat(resp.getDetail()).isNull();
        assertThat(resp.getStatus()).isEqualTo(409);
    }

    @Test
    void ofSummarized_longMultiline_splitsMessageAndDetail() {
        String full = "error: the stack is currently locked by 1 lock(s).\n"
                + "  s3://pulumi-state/.pulumi/locks/organization/...\n"
                + "x".repeat(400);
        ErrorResponse resp = ErrorResponse.ofSummarized(ErrorCode.UPSTREAM_FAILED, full);

        assertThat(resp.getMessage()).startsWith("error: the stack is currently locked");
        assertThat(resp.getMessage().length()).isLessThan(260);
        assertThat(resp.getDetail()).isEqualTo(full);
        assertThat(resp.getStatus()).isEqualTo(502);
    }

    @Test
    void withHint_serializedWhenPresent() throws Exception {
        ErrorResponse resp = ErrorResponse.of(ErrorCode.UPSTREAM_FAILED, "Pulumi preview failed")
                .withHint("IAM policy 에 ec2:Describe* 추가 후 재시도하세요.");

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"hint\"");
        assertThat(json).contains("ec2:Describe*");
    }

    @Test
    void stateConflict_statusMatchesHttp409() {
        // 불변식: body.status == ErrorCode.status (HTTP 와 동일하게 사용됨).
        assertThat(ErrorResponse.of(ErrorCode.STATE_CONFLICT).getStatus()).isEqualTo(409);
        assertThat(ErrorResponse.of(ErrorCode.UPSTREAM_FAILED).getStatus()).isEqualTo(502);
    }

    @Test
    void withLinks_serializedWhenPresent() throws Exception {
        ErrorResponse resp = ErrorResponse.of(ErrorCode.CLUSTER_NOT_FOUND, "Cluster 'x' not found")
                .withLinks(java.util.Map.of("clusterList", "/v1/clusters"));

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"links\"");
        assertThat(json).contains("/v1/clusters");
    }

    @Test
    void links_omittedWhenEmpty() throws Exception {
        String json = mapper.writeValueAsString(ErrorResponse.of(ErrorCode.NOT_FOUND));
        assertThat(json).doesNotContain("\"links\"");
    }
}
