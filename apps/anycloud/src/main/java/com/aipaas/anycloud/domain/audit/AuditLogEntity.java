package com.aipaas.anycloud.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "principal", length = 128)
    private String principal;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    /**
     * V25 — nullable. service-layer {@code @Audited} 가 HTTP context 없이 호출되는
     * 경우 (scheduler / @Async / @AuditAspect 가 controller 외에서 호출) NULL 허용.
     */
    @Column(name = "http_method", length = 8)
    private String httpMethod;

    /** V25 — nullable. 위와 동일 사유. */
    @Column(name = "path", length = 512)
    private String path;

    @Column(name = "action", length = 64, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "MEDIUMTEXT")
    private String errorMessage;

    @Column(name = "request_summary", columnDefinition = "MEDIUMTEXT")
    private String requestSummary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
