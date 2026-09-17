package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import java.util.List;

/**
 * Pulumi / CSP API stderr 를 transient / permanent 로 분류해 적절한 {@link ProvisioningException}
 * 으로 wrap.
 *
 * <p>분류 매트릭스는 {@code docs/operations/credential-failure-policy.md} § 2 가 단일 진실. 본 클래스의
 * 패턴 추가는 반드시 정책 문서를 먼저 갱신한 후 반영.
 *
 * <p>매칭 순서: permanent 우선. 어떤 패턴도 매칭 안 되면 default transient (외부 시스템의 일시 hiccup
 * 으로 가정 — false positive 가 false negative 보다 운영자에게 덜 위험).
 */
public final class CspStderrClassifier {

    private CspStderrClassifier() {}

    /** Case-insensitive substring 매칭 — Pattern 컴파일 비용 회피, stderr 가 보통 짧음. */
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
            // Azure
            "AuthorizationFailed",
            "InvalidAuthenticationToken",
            "Forbidden",
            // Alibaba
            "InvalidAccessKeyId.NotFound",
            "Forbidden.RAM",
            // OCI
            "NotAuthenticated",
            "NotAuthorizedOrNotFound",
            // DigitalOcean
            "Unable to authenticate you",
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

    /** Pulumi CLI 호출 전용 — summary 가 항상 "Pulumi <action> failed" 형태. */
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
