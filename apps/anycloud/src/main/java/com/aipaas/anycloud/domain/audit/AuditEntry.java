package com.aipaas.anycloud.domain.audit;

import lombok.Builder;

/**
 * 단일 audit 기록 의 모든 입력. 빌더 패턴으로 부분 필드만 채워도 OK.
 */
@Builder
public record AuditEntry(
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
        String requestSummary) {}
