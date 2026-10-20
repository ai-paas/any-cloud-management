package com.aipaas.anycloud.common.error.exception.provisioning;

import java.util.List;
import java.util.Locale;

/**
 * 자격증명 실패를 사용자 조치 기준으로 분류.
 *
 * <p>값 없음, 키 불일치, 권한 없음은 조치 지점이 다름에도 동일 메시지로 전달. CSP 원문은
 * provider 별 표현이 달라 그대로 노출해도 조치 판단 불가.
 */
public enum CredentialFailureKind {
    /** 필요한 키 미등록. preflight 에서 검출. */
    MISSING_VALUE("자격증명에 필요한 값이 없습니다. 자격증명을 다시 등록해주세요."),

    /** 키가 틀렸거나 만료됐다. */
    AUTHENTICATION_FAILED("자격증명이 거부됐습니다. 키가 만료되었거나 값이 잘못되지 않았는지 확인해주세요."),

    /** 인증 성공, 해당 작업 권한 없음. */
    PERMISSION_DENIED("인증은 됐지만 권한이 없습니다. 해당 계정의 IAM 정책을 확인해주세요."),

    /** CSP 측 사유. 자격증명 문제 아님. */
    UPSTREAM_UNAVAILABLE("CSP 가 일시적으로 응답하지 않거나 용량이 부족합니다. 잠시 후 다시 시도해주세요."),

    /** 해당 프로바이더에 자격증명 검증 호출 부재. 확인 불가로 응답. */
    NOT_VERIFIABLE("이 프로바이더는 아직 값이 맞는지 확인할 방법이 없습니다. 프로비저닝을 돌려야 알 수 있습니다."),

    /** 분류 실패. 임의 분류 시 잘못된 안내 전달. */
    UNKNOWN("원인을 특정하지 못했습니다. 아래 원본 메시지를 확인해주세요.");

    private final String hint;

    CredentialFailureKind(String hint) {
        this.hint = hint;
    }

    /** 사용자가 할 일. */
    public String hint() {
        return hint;
    }

    /** 값이 아예 없는 것과, 있는데 읽히지 않는 것은 둘 다 재등록으로 고친다. */
    private static final List<String> MISSING = List.of(
            "is required",
            "missing required",
            "not configured",
            "failed to read",
            "failed to sign",
            "could not parse",
            "malformed");

    /**
     * 권한 쪽을 인증보다 먼저 본다. OCI 의 {@code NotAuthorizedOrNotFound} 처럼 한 문자열에서 둘 다
     * 읽히는 경우가 있는데, 인증은 통과한 상태이므로 권한을 보게 하는 편이 실제로 맞다.
     */
    private static final List<String> PERMISSION = List.of(
            "accessdenied",
            "permission_denied",
            "authorizationfailed",
            "notauthorizedornotfound",
            "unauthorizedoperation",
            "forbidden",
            "403");

    private static final List<String> AUTHENTICATION = List.of(
            "invalidaccesskeyid",
            "signaturedoesnotmatch",
            "invalid_grant",
            "unauthenticated",
            "notauthenticated",
            "invalidauthenticationtoken",
            "unable to authenticate you",
            "401");

    private static final List<String> UPSTREAM = List.of(
            "serviceunavailable",
            "internalservererror",
            "internalerror",
            "unavailable",
            "out of host capacity",
            "throttling",
            "toomanyrequests",
            "requesttimeout",
            "deadline_exceeded");

    public static CredentialFailureKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String text = raw.toLowerCase(Locale.ROOT);
        if (contains(text, MISSING)) {
            return MISSING_VALUE;
        }
        if (contains(text, UPSTREAM)) {
            return UPSTREAM_UNAVAILABLE;
        }
        if (contains(text, PERMISSION)) {
            return PERMISSION_DENIED;
        }
        if (contains(text, AUTHENTICATION)) {
            return AUTHENTICATION_FAILED;
        }
        return UNKNOWN;
    }

    private static boolean contains(String text, List<String> tokens) {
        return tokens.stream().anyMatch(text::contains);
    }
}
