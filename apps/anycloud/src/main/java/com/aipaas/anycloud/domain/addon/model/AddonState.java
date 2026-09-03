package com.aipaas.anycloud.domain.addon.model;

/**
 * Cluster addon 의 install lifecycle state.
 *
 * <pre>
 *   PENDING    : row 생성, cluster ACTIVE 대기 또는 enqueue 직전.
 *   ENQUEUED   : RabbitMQ publish 완료, listener consume 대기.
 *   INSTALLING : listener 가 helm install 진행 중.
 *   SUCCEEDED  : helm release 정상 deployed.
 *   FAILED     : install 실패, last_error 채워짐. retry endpoint 로 재시도 가능.
 *   DELETING   : uninstall 요청, listener 처리 중.
 *   DELETED    : uninstall 완료 (soft delete — row 보존).
 * </pre>
 *
 * <p>State 전이는 단방향 흐름 위주 (PENDING → ENQUEUED → INSTALLING → SUCCEEDED|FAILED).
 * FAILED → ENQUEUED (retry) 와 SUCCEEDED → DELETING → DELETED 가 예외적 cycle.
 */
public enum AddonState {
    PENDING,
    ENQUEUED,
    INSTALLING,
    SUCCEEDED,
    FAILED,
    DELETING,
    DELETED;

    /** 재실행 가능한 terminal state — UI 가 retry 버튼 노출. */
    public boolean isRetryable() {
        return this == FAILED;
    }

    /** install path 가 active 한 상태 — 중복 enqueue 방지. */
    public boolean isInFlight() {
        return this == ENQUEUED || this == INSTALLING || this == DELETING;
    }
}
