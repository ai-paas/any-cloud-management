package com.aipaas.anycloud.domain.audit;

/**
 * Audit log 쓰기 단일 진입점. 인터셉터 + 비즈니스 로직 양쪽에서 호출 가능.
 */
public interface AuditLogger {

    /**
     * 표준 액션 명명 규칙: {@code <domain>.<verb>}. 예: vmCluster.create, charts.rollback,
     * cluster.delete. resourceType 은 도메인과 동일하게 둠 (cluster, vmCluster, helmRelease 등).
     */
    void record(AuditEntry entry);
}
