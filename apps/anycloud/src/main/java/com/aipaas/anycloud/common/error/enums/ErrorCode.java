package com.aipaas.anycloud.common.error.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.ToString;

/**
 * <pre>
 * ClassName : ErrorCode
 * Type : enum
 * Description : 에러 코드, 에러 메시지를 포함하고 있는 enum입니다.
 * Related : ErrorResponse
 * </pre>
 */
@Getter
@ToString
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR(500, "서버에 문제가 발생했습니다."),
    RUNTIME_EXCEPTION(400, "잘못된 요청입니다."),
    INVALID_INPUT_VALUE(400, "유효하지 않는 입력 값입니다."),
    ENTITY_NOT_FOUND(400, "데이터를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(405, "허용되지 않는 메소드입니다."),
    INVALID_TYPE_VALUE(400, "유효하지 않은 유형 값입니다."),
    NOT_FOUND(404, "찾을 수 없습니다."),
    FORBIDDEN(403, "접근 권한이 없어 거부되었습니다."),
    ACCESS_DENIED_EXCEPTION(401, "인증 정보가 유효하지 않습니다."),
    DUPLICATE(409, "중복된 데이터가 있습니다."),
    NO_BODY(400, "입력된 바디 또는 파라미터가 없습니다."),
    DATA_INTEGRITY(403, "데이터가 정상적으로 처리되지 못했습니다."),
    //  요청 자체는 valid 하지만 현재 리소스 상태와 충돌 (예: DELETED cluster 재시도).
    // 기존엔 INVALID_INPUT_VALUE(400) 로 잘못 표기 + HTTP 409 와 body status 불일치까지 발생.
    STATE_CONFLICT(409, "현재 리소스 상태와 충돌하는 요청입니다."),
    //  외부 시스템 (Pulumi CLI / CSP API / RustFS) 호출 실패. 입력 문제가 아님을 명시.
    UPSTREAM_FAILED(502, "외부 시스템 호출에 실패했습니다 (Pulumi/CSP 등). 자격증명·권한·외부 상태를 확인하세요."),

    // Cluster related errors
    CLUSTER_NOT_FOUND(404, "클러스터를 찾을 수 없습니다."),
    CLUSTER_CONNECTION_FAILED(503, "클러스터 연결에 실패했습니다."),
    CLUSTER_INVALID_CONFIG(400, "클러스터 설정이 유효하지 않습니다."),
    CLUSTER_BOOTSTRAP_FAILED(500, "클러스터 부트스트랩 실패."),
    CLUSTER_PULUMI_FAILED(500, "Pulumi provisioning 실패."),

    // Provisioning config (UX #7 — generic INVALID_INPUT_VALUE 보다 세분화)
    PROVISIONING_PROVIDER_UNSUPPORTED(400, "지원하지 않는 provider."),
    PROVISIONING_CONFIG_MISSING_KEY(400, "필수 provisioning config 키 누락."),
    PROVISIONING_CONFIG_INVALID_VALUE(400, "Provisioning config 값이 유효하지 않습니다."),

    // Agent routing 실패 → 즉시 503. 모든 day-2 ops (K8s + Helm) 는 agent-only path 라
    // 본 에러로 떨어진다. 운영자 점검 포인트: agent ACTIVE / cluster_agent 테이블 / heartbeat 신선도.
    AGENT_UNAVAILABLE(503, "Cluster agent 가 응답하지 않습니다. agent ACTIVE 상태 / heartbeat 확인 필요."),

    // Fleet upgrade — 운영 의미가 명확한 도메인 코드. generic DUPLICATE / ENTITY_NOT_FOUND 보다
    // runbook 매핑이 깔끔. monitoring 의 PromQL alert 도 본 코드 기준으로 분류 가능.
    AGENT_NOT_ACTIVE(404, "ACTIVE 상태의 cluster agent 가 없습니다 — connectivity 복구 후 재시도."),
    UPGRADE_IN_PROGRESS(409, "이미 진행 중인 upgrade 가 있습니다. 완료/중단 후 재시도."),

    // agent 의 RESTMapper 가 입력을 resolve 못한 케이스. 4xx — caller 가 retry 해도 같은 결과.
    // 응답 metadata 에 suggestions (Levenshtein top-3) 포함 — type-ahead UI 활용.
    UNSUPPORTED_KIND(404, "Cluster 가 해당 kind 를 지원하지 않습니다. CRD 설치 여부 / 오타 확인."),

    // Agent 의 allowlist 가 chart 또는 namespace 를 거부한 케이스. 503 (agent 비활성) 와 구분.
    // 운영자가 ConfigMap 에 chart / namespace 등록하면 해소.
    CHART_NOT_ALLOWED(403, "Agent allowlist 에 등록되지 않은 chart 입니다. ConfigMap 의 allowed_charts 에 추가 필요."),
    AGENT_NAMESPACE_NOT_ALLOWED(
            403, "Agent allowlist 에 등록되지 않은 namespace 입니다. ConfigMap 의 allowed_namespaces 에 추가 필요."),
    AGENT_PERMISSION_DENIED(403, "Agent 정책이 요청을 거부했습니다 — allowlist / resource_policy 확인."),

    // Agent 가 helm install 자체는 시도했으나 helm SDK 가 chart 위치 / 다운로드 실패. caller 가 fixable.
    // 예: repo 가 agent 에 helm-add 안 됨 / repo URL 빈 값 / 잘못된 chart 형식.
    HELM_CHART_RESOLUTION_FAILED(400, "Agent 가 chart 를 찾지 못했습니다. helm repo URL / chart 이름 / 버전 확인."),
    // Agent 가 helm install 자체는 도달했으나 K8s apply / hook 단계 실패. agent 정상이고 caller-fixable.
    HELM_INSTALL_FAILED(500, "Helm install 이 K8s apply 단계에서 실패. 응답의 reason / agent 로그 확인.");

    private final int status;
    private final String message;

    ErrorCode(final int status, final String message) {
        this.status = status;
        this.message = message;
    }
}
