package com.aipaas.anycloud.domain.audit.internal;

import com.aipaas.anycloud.domain.audit.AuditLogEntity;
import com.aipaas.anycloud.domain.audit.AuditLogRepository;
import com.aipaas.anycloud.domain.audit.AuditLogResponse;
import com.aipaas.anycloud.domain.audit.AuditLogService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository repository;

    @Override
    public List<AuditLogResponse> search(
            LocalDateTime since,
            LocalDateTime until,
            String resourceType,
            String resourceId,
            String action,
            String principal,
            Pageable pageable) {
        List<AuditLogEntity> rows =
                repository.search(since, until, resourceType, resourceId, action, principal, pageable);
        return rows.stream().map(AuditLogServiceImpl::toDto).toList();
    }

    private static AuditLogResponse toDto(AuditLogEntity e) {
        return AuditLogResponse.builder()
                .id(e.getId())
                .requestId(e.getRequestId())
                .principal(e.getPrincipal())
                .clientIp(e.getClientIp())
                .httpMethod(e.getHttpMethod())
                .path(e.getPath())
                .action(e.getAction())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .statusCode(e.getStatusCode())
                .durationMs(e.getDurationMs())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
