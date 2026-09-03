package com.aipaas.anycloud.domain.audit;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {

    /**
     * TTL retention — 지정 시각 이전의 audit log 일괄 삭제. row 수 (UI 노출) 한정 + DB 디스크 사용량 cap.
     * scheduler 가 호출.
     *
     * @return 삭제된 row 수.
     */
    @Modifying
    @Query("DELETE FROM AuditLogEntity a WHERE a.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);

    /**
     * 시간 윈도우 + (선택) 필터로 페이지 단위 조회. 최신 → 과거 순서.
     */
    @Query("SELECT a FROM AuditLogEntity a "
            + "WHERE (:since IS NULL OR a.createdAt >= :since) "
            + "  AND (:until IS NULL OR a.createdAt < :until) "
            + "  AND (:resourceType IS NULL OR a.resourceType = :resourceType) "
            + "  AND (:resourceId IS NULL OR a.resourceId = :resourceId) "
            + "  AND (:action IS NULL OR a.action = :action) "
            + "  AND (:principal IS NULL OR a.principal = :principal) "
            + "ORDER BY a.createdAt DESC")
    List<AuditLogEntity> search(
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("action") String action,
            @Param("principal") String principal,
            Pageable pageable);
}
