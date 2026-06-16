package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Workflow 메시지 한 건이 어떻게 처리되었는지의 기록.
 * <p>
 * 멱등성 가드와 결합되어 운영 시점에서 RabbitMQ 재전달 / 단계 비정상 도착 / 실행 실패 분포를 보여준다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "workflow_message_log")
public class WorkflowMessageLogEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "vm_cluster_id", length = 36)
    private String vmClusterId;

    @Column(name = "cluster_name", length = 45)
    private String clusterName;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", length = 30, nullable = false)
    private VmClusterWorkflowStep step;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 30, nullable = false)
    private WorkflowMessageLogResult result;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "MEDIUMTEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
