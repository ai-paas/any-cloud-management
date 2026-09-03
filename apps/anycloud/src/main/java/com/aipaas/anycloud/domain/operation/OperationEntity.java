package com.aipaas.anycloud.domain.operation;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "operation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 48, nullable = false)
    private OperationType type;

    @Column(name = "resource_type", length = 48, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 128, nullable = false)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 24, nullable = false)
    private OperationState state;

    @Column(name = "current_step", length = 48)
    private String currentStep;

    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(name = "total_steps")
    private Integer totalSteps;

    @Column(name = "percent")
    private Integer percent;

    @Column(name = "request_payload", columnDefinition = "MEDIUMTEXT")
    private String requestPayload;

    @Column(name = "result_payload", columnDefinition = "MEDIUMTEXT")
    private String resultPayload;

    @Column(name = "error_message", columnDefinition = "MEDIUMTEXT")
    private String errorMessage;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "principal", length = 128)
    private String principal;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
