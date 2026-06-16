package com.aipaas.anycloud.domain.audit;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;

/**
 * AuditLogController 가 repository 를 직접 import 하던 layering 위반 해소.
 * service 계층이 (검색 정책 / 마스킹 / RBAC) 결정을 흡수.
 */
public interface AuditLogService {

    /**
     * 시간 윈도우 + 필터 검색. service 가 DTO 변환까지 수행 — controller 는 entity 모름.
     */
    List<AuditLogResponse> search(
            LocalDateTime since,
            LocalDateTime until,
            String resourceType,
            String resourceId,
            String action,
            String principal,
            Pageable pageable);
}
