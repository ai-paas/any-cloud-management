package com.aipaas.anycloud.domain.audit.model;

import java.time.LocalDateTime;

/**
 * Audit log entry — append-only. 사용자 / system 의 모든 mutating action 추적용.
 *
 * <p>Append-only 라 lifecycle method 없음 (단순 immutable read view).
 */
public record AuditLog(
        String id,
        String requestId,
        String principal,
        String clientIp,
        String httpMethod,
        String path,
        String action,
        String resourceType,
        String resourceId,
        Integer statusCode,
        Long durationMs,
        String errorMessage,
        String requestSummary,
        LocalDateTime createdAt) {

    /** 4xx/5xx 응답 — 에러 / 실패 (drill-down 우선순위 ↑). */
    public boolean isFailure() {
        return statusCode != null && statusCode >= 400;
    }

    /** 5xx 응답 — server-side error. */
    public boolean isServerError() {
        return statusCode != null && statusCode >= 500;
    }
}
