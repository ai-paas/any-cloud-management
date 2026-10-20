package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import java.util.List;

/**
 * Pulumi, CSP stderr 를 transient 와 permanent 로 분류해 {@link ProvisioningException} 으로 변환.
 *
 * <p>분류 매트릭스의 단일 출처는 {@code docs/operations/credential-failure-policy.md} § 2.
 * 패턴 추가 시 정책 문서 선행 수정.
 *
 * <p>permanent 패턴 우선 매칭, 미매칭 시 transient 로 판정. 영구 실패를 무음 처리하는 것보다
 * 재시도 후 재실패가 안전.
 */
public final class CspStderrClassifier {

    private CspStderrClassifier() {}

    /** Case-insensitive substring 매칭: Pattern 컴파일 비용 회피, stderr 가 보통 짧음. */
    private static final List<String> PERMANENT_AUTH_TOKENS = List.of(
            // AWS
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "UnauthorizedOperation",
            "AccessDenied",
            // GCP
            "invalid_grant",
            "UNAUTHENTICATED",
            "PERMISSION_DENIED",
            // Alibaba
            "InvalidAccessKeyId.NotFound",
            "Forbidden.RAM",
            // OCI
            "NotAuthenticated",
            "NotAuthorizedOrNotFound",
            // OpenStack
            "401 Unauthorized",
            "403 Forbidden");

    private static final List<String> TRANSIENT_TOKENS = List.of(
            "ServiceUnavailable",
            "503",
            "InternalServerError",
            "InternalError",
            "INTERNAL",
            "UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "OperationTimedOut",
            "ThrottlingException",
            "Throttling",
            "TooManyRequests",
            "RequestTimeout",
            "timeout",
            "connection refused");

    /** stderr 를 분류 후 적절한 ProvisioningException 생성. */
    public static ProvisioningException classify(String summary, String rawStderr) {
        String detail = blankToNull(rawStderr);
        String message = summary + (detail == null ? "" : ": " + detail);
        if (containsAny(detail, PERMANENT_AUTH_TOKENS)) {
            return new PermanentProvisioningFailure(message, ErrorCode.UPSTREAM_FAILED);
        }
        if (containsAny(detail, TRANSIENT_TOKENS)) {
            return new TransientProvisioningFailure(message);
        }
        return new TransientProvisioningFailure(message);
    }

    /** Pulumi CLI 호출 전용: summary 가 항상 "Pulumi <action> failed" 형태. */
    public static ProvisioningException classifyPulumi(String action, String rawStderr) {
        String detail = blankToNull(rawStderr);
        String message = "Pulumi " + action + " failed" + (detail == null ? "" : ": " + detail);
        if (containsAny(detail, PERMANENT_AUTH_TOKENS)) {
            return new PermanentProvisioningFailure(message, ErrorCode.UPSTREAM_FAILED);
        }
        return new PulumiExecutionException(message);
    }

    /** stderr 가 비면 stdout 으로 fallback. */
    public static ProvisioningException classifyPulumi(String action, String stderr, String stdout, String exitHint) {
        String detail = blankToNull(stderr);
        if (detail == null) {
            detail = blankToNull(stdout);
        }
        if (detail == null) {
            detail = blankToNull(exitHint);
        }
        return classifyPulumi(action, detail);
    }

    /** null / 공백(whitespace-only) → null 로 정규화하고 trim. */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        String lower = haystack.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
