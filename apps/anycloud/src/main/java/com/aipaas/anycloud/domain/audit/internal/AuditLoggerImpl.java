package com.aipaas.anycloud.domain.audit.internal;

import com.aipaas.anycloud.domain.audit.AuditEntry;
import com.aipaas.anycloud.domain.audit.AuditLogEntity;
import com.aipaas.anycloud.domain.audit.AuditLogRepository;
import com.aipaas.anycloud.domain.audit.AuditLogger;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Audit 기록을 비동기로 DB 에 저장. async 실패해도 비즈니스 로직은 진행.
 * <p>
 * Async pool 은 default(=kubernetesExecutor)를 사용. audit volume 이 큰 환경에선
 * 전용 pool 로 분리 권장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLoggerImpl implements AuditLogger {

    private static final int MAX_PATH = 512;
    private static final int MAX_SUMMARY = 8000;
    private static final int MAX_ERR = 8000;

    private final AuditLogRepository repository;

    @Override
    @Async
    public void record(AuditEntry e) {
        if (e == null) return;
        try {
            AuditLogEntity entity = AuditLogEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .requestId(truncate(e.requestId(), 64))
                    .principal(truncate(e.principal(), 128))
                    .clientIp(truncate(e.clientIp(), 64))
                    .httpMethod(truncate(e.httpMethod(), 8))
                    .path(truncate(e.path(), MAX_PATH))
                    .action(truncate(e.action(), 64))
                    .resourceType(truncate(e.resourceType(), 64))
                    .resourceId(truncate(e.resourceId(), 128))
                    .statusCode(e.statusCode())
                    .durationMs(e.durationMs())
                    .errorMessage(truncate(e.errorMessage(), MAX_ERR))
                    .requestSummary(truncate(e.requestSummary(), MAX_SUMMARY))
                    .build();
            repository.save(entity);
        } catch (Exception ex) {
            // audit 실패가 비즈니스 흐름을 방해하면 금지. 로그만 남기고 swallow.
            log.warn(
                    "Audit save failed (non-fatal): action={}, resource={}/{}, cause={}",
                    e.action(),
                    e.resourceType(),
                    e.resourceId(),
                    ex.toString());
        }
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
