package com.aipaas.anycloud.common.error.exception.provisioning;

import java.util.List;
import java.util.Locale;

/**
 * 실패 원문을 사용자가 읽을 수 있는 한 줄로 바꾼다.
 *
 * <p>{@code lastError} 에는 Pulumi stdout 이 통째로 들어간다. 수백 줄 안에서 {@code Out of host
 * capacity} 한 조각을 찾아내는 것은 이 시스템을 만든 사람만 할 수 있다.
 *
 * <p>원문은 지우지 않는다 — 분류에 없는 실패는 여전히 원문으로만 알 수 있다.
 */
public enum ProvisioningFailureReason {
    /** OCI 는 목록에 있어도 그 AD 에 자리가 없을 수 있다. 시점에 따라 바뀐다. */
    OUT_OF_CAPACITY(
            "선택한 리전에 지금 만들 수 있는 자리가 없습니다.",
            "다른 인스턴스 타입이나 리전을 고르거나, 잠시 뒤 다시 시도하세요.",
            List.of("out of host capacity", "outofcapacity", "insufficient capacity", "capacity is not available")),

    QUOTA_EXCEEDED(
            "계정의 사용 한도를 넘었습니다.",
            "CSP 콘솔에서 한도를 올리거나 쓰지 않는 자원을 먼저 정리하세요.",
            List.of(
                    "quotaexceeded",
                    "limitexceeded",
                    "instancelimitexceeded",
                    "vcpulimitexceeded",
                    "exceeded quota",
                    "quota exceeded")),

    CREDENTIAL_REJECTED(
            "자격증명이 거절됐습니다.",
            "자격증명 화면에서 상태를 확인하고, 만료됐다면 새로 발급해 등록하세요.",
            List.of(
                    "invalidaccesskeyid",
                    "signaturedoesnotmatch",
                    "unauthorizedoperation",
                    "accessdenied",
                    "invalid_grant",
                    "unauthenticated",
                    "permission_denied",
                    "forbidden.ram",
                    "notauthenticated",
                    "notauthorizedornotfound",
                    "401 unauthorized",
                    "403 forbidden")),

    /** 이미지 ID 는 리전마다 다르고 주기적으로 갈린다. */
    IMAGE_NOT_FOUND(
            "고른 OS 이미지를 찾을 수 없습니다.",
            "이미지 목록에서 다시 고르세요. 이미지 식별자는 리전마다 다르고 주기적으로 바뀝니다.",
            List.of(
                    "invalidimageid",
                    "image not found",
                    "imagenotfound",
                    "invalidimagename",
                    // IBM 은 없는 이미지를 찾으면 pulumi-yaml 이 패닉해 Go 스택이 그대로 올라온다.
                    "getisimage",
                    "registering variable [image]")),

    INSTANCE_TYPE_UNAVAILABLE(
            "고른 인스턴스 타입을 이 리전에서 쓸 수 없습니다.",
            "인스턴스 타입을 다시 고르세요. 세대마다 제공 리전이 다릅니다.",
            List.of(
                    "invalidinstancetype",
                    "unsupported instance",
                    "flavor.*not found",
                    "invalid flavor",
                    "shape is not supported",
                    "notsupportedendpoint")),

    /** 기본 계정이 CSP 마다 다르다. Alibaba 의 Ubuntu 이미지에는 ubuntu 계정이 없다. */
    NODE_LOGIN_REJECTED(
            "노드에 접속하지 못했습니다.",
            "인스턴스는 만들어졌지만 SSH 가 거절됐습니다. 이미지의 기본 계정과 보안 그룹의 22번 포트를 확인하세요.",
            List.of("permission denied (publickey)", "permission denied, please try again", "ssh: handshake failed")),

    NODE_UNREACHABLE(
            "노드에 닿지 못했습니다.",
            "보안 그룹과 방화벽에서 22번 포트가 열려 있는지, 사설망이라면 점프 호스트 설정이 맞는지 확인하세요.",
            List.of("i/o timeout", "no route to host", "connection refused", "dial tcp")),

    CONFIG_MISSING(
            "필요한 설정이 빠졌습니다.",
            "생성 화면에서 필수 항목을 채운 뒤 다시 시도하세요.",
            List.of("missing required provisioning config", "required config")),

    ADDRESS_EXHAUSTED(
            "공인 주소를 더 받을 수 없습니다.",
            "쓰지 않는 Floating IP 나 Elastic IP 를 반납한 뒤 다시 시도하세요.",
            List.of("addresslimitexceeded", "no more ips available", "floatingiplimitexceeded", "eip quota"));

    private final String summary;
    private final String hint;
    private final List<String> tokens;

    ProvisioningFailureReason(String summary, String hint, List<String> tokens) {
        this.summary = summary;
        this.hint = hint;
        this.tokens = tokens;
    }

    public String summary() {
        return summary;
    }

    public String hint() {
        return hint;
    }

    /**
     * 원문에서 아는 실패를 찾는다.
     *
     * <p>앞에 선언된 것이 먼저다 — 용량 부족 메시지에 "capacity" 와 "quota" 가 함께 오는 CSP 가
     * 있어, 더 구체적인 쪽을 위에 둔다. 모르는 실패는 {@code null} 이고 화면은 원문을 보여준다.
     */
    public static ProvisioningFailureReason from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (ProvisioningFailureReason reason : values()) {
            for (String token : reason.tokens) {
                if (token.contains(".*") ? lower.matches("(?s).*" + token + ".*") : lower.contains(token)) {
                    return reason;
                }
            }
        }
        return null;
    }
}
