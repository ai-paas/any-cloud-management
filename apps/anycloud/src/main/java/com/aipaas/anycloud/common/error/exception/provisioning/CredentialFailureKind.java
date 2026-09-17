package com.aipaas.anycloud.common.error.exception.provisioning;

import java.util.List;
import java.util.Locale;

/**
 * 자격증명 실패를 사용자가 할 일 기준으로 나눈다.
 *
 * <p>"값 없음", "키가 틀림", "권한 없음" 은 고치는 곳이 전혀 다른데 같은 메시지로 나왔다. CSP 가
 * 주는 원문은 provider 마다 표현이 달라 그대로 보여줘도 무엇을 해야 하는지 알 수 없다.
 */
public enum CredentialFailureKind {
    /** 필요한 키가 등록되지 않았다. preflight 가 잡는다. */
    MISSING_VALUE("자격증명에 필요한 값이 없습니다. 자격증명을 다시 등록해주세요."),

    /** 키가 틀렸거나 만료됐다. */
    AUTHENTICATION_FAILED("자격증명이 거부됐습니다. 키가 만료되었거나 값이 잘못되지 않았는지 확인해주세요."),

    /** 인증은 됐는데 이 작업을 할 권한이 없다. */
    PERMISSION_DENIED("인증은 됐지만 권한이 없습니다. 해당 계정의 IAM 정책을 확인해주세요."),

    /** CSP 쪽 사정이다. 자격증명 문제가 아니다. */
    UPSTREAM_UNAVAILABLE("CSP 가 일시적으로 응답하지 않거나 용량이 부족합니다. 잠시 후 다시 시도해주세요."),

    /** 이 프로바이더는 자격증명을 실제로 검증하는 호출이 없다. 모른다고 말해야 한다. */
    NOT_VERIFIABLE("이 프로바이더는 아직 값이 맞는지 확인할 방법이 없습니다. 프로비저닝을 돌려야 알 수 있습니다."),

    /** 분류하지 못했다. 억지로 분류하면 엉뚱한 안내가 나간다. */
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
