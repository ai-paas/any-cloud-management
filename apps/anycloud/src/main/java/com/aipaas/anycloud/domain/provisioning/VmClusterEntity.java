package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * VM 기반 클러스터 생성 이력과 상태를 추적하는 엔티티입니다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "vm_cluster")
public class VmClusterEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 376729864986626054L;

    @Id
    @Size(max = 36)
    @Column(name = "id", nullable = false, length = 36)
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotNull
    @Size(max = 45)
    @Column(name = "cluster_name", nullable = false, length = 45)
    private String clusterName;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    @NotNull
    @Size(max = 100)
    @Column(name = "cluster_provider", nullable = false, length = 100)
    private String clusterProvider;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", nullable = false, length = 50)
    private VmClusterStatus provisioningStatus;

    @NotNull
    @Size(max = 100)
    @Column(name = "stack_name", nullable = false, length = 100)
    private String stackName;

    @Size(max = 100)
    @Column(name = "region", length = 100)
    private String region;

    @Size(max = 50)
    @Column(name = "environment", length = 50)
    private String environment;

    @Size(max = 45)
    @Column(name = "active_request_key", length = 45, unique = true)
    private String activeRequestKey;

    @Size(max = 36)
    @Column(name = "credential_id", length = 36)
    private String credentialId;

    @Size(max = 100)
    @Column(name = "credential_name", length = 100)
    private String credentialName;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_source_type", length = 30)
    private CspCredentialSourceType credentialSourceType;

    @Lob
    @Column(name = "request_config", columnDefinition = "LONGTEXT")
    private String requestConfig;

    @Lob
    @Column(name = "raw_outputs", columnDefinition = "LONGTEXT")
    private String rawOutputs;

    @Lob
    @Column(name = "last_error", columnDefinition = "MEDIUMTEXT")
    private String lastError;

    /**
     * 마지막 실패의 분류 코드. ErrorResponse 의 code 와 동일 체계 — UI 가 메시지 대신
     * 코드로 분기 가능. fail() 이 예외에서 추론해 설정, markReady/markDeleteCompleted 가 클리어.
     */
    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Lob
    @Column(name = "bootstrap_log", columnDefinition = "MEDIUMTEXT")
    private String bootstrapLog;

    /**
     * BOOTSTRAP 단계 내부의 sub-step label — MASTER_INIT / WORKER_JOIN / NODES_READY 등.
     * BOOTSTRAP 은 20~30분 걸려 "어디서 멈췄나" 가시성이 핵심. progress reporter 가 갱신,
     * markReady 가 클리어.
     */
    @Column(name = "current_sub_step", length = 50)
    private String currentSubStep;

    @Column(name = "sub_step_started_at")
    private LocalDateTime subStepStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_workflow_step", length = 30)
    private com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep currentWorkflowStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_successful_step", length = 30)
    private com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep lastSuccessfulStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failed_step", length = 30)
    private com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep lastFailedStep;

    @Column(name = "workflow_retry_count", nullable = false)
    @Builder.Default
    private Integer workflowRetryCount = 0;

    /**
     * 가장 최근에 처리한 workflow 메시지의 ID. 동일 messageId 가 재전달되면 orchestrator 가 스킵한다.
     */
    @Column(name = "last_processed_workflow_message_id", length = 36)
    private String lastProcessedWorkflowMessageId;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "provisioning_started_at")
    private LocalDateTime provisioningStartedAt;

    @Column(name = "bootstrapping_started_at")
    private LocalDateTime bootstrappingStartedAt;

    @Column(name = "verifying_started_at")
    private LocalDateTime verifyingStartedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "deleting_started_at")
    private LocalDateTime deletingStartedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "cluster_registered", nullable = false)
    @Builder.Default
    private Boolean clusterRegistered = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // ============= State transition helper =============

    /** SLF4J logger — static (Hibernate proxy 가 instance field 를 갖지 못함). */
    private static final org.slf4j.Logger STATE_LOG =
            org.slf4j.LoggerFactory.getLogger(VmClusterEntity.class.getName() + ".state");

    /**
     * Validated state transition. {@link VmClusterStatus#canTransitionTo} 로 graph 검증 + status set
     * + audit history row insert (recorder 통해).
     *
     * <p>관측 모드 (default) — invalid transition 시 log.warn 만. strict 모드 (throw) 는
     * {@code anycloud.vm-cluster.state-machine.strict=true} 로 전환 (별도 caller config — entity 가 직접 읽지 않음).
     *
     * <p>State history 자동 기록: {@link SpringBeanHolder} 통해 {@code VmClusterStateHistoryRecorder}
     * 조회 후 호출. JPA entity 가 service bean 을 직접 inject 받을 수 없으므로 static lookup 사용.
     * test 환경 (ApplicationContext 없음) 에선 recorder null 이라 silent skip — entity 동작은 동일.
     *
     * @param next 새 상태
     * @param reason 호출 위치 / 사유 (log + audit 용도). 예: "workflow.BOOTSTRAP.ok", "scale.start"
     */
    public void transitionTo(VmClusterStatus next, String reason) {
        VmClusterStatus current = this.provisioningStatus;
        boolean invalid = current != null && !current.canTransitionTo(next);
        if (invalid) {
            // Strict mode (anycloud.vm-cluster.state-machine.strict=true) — throw 로 즉시 차단.
            // Observation mode (default) — log.warn + apply (audit history 가 invalid 도 row 로 남김).
            var props = com.aipaas.anycloud.common.util.SpringBeanHolder.beanOrNull(
                    com.aipaas.anycloud.domain.provisioning.properties.VmClusterStateMachineProperties.class);
            if (props != null && props.isStrict()) {
                throw new com.aipaas.anycloud.common.error.exception.provisioning.StateConflictException(String.format(
                        "Invalid VmCluster state transition (strict mode): cluster=%s, %s → %s, reason=%s",
                        this.id, current, next, reason));
            }
            STATE_LOG.warn(
                    "vmcluster {} state transition VIOLATION: {} → {} (reason={}) — observation mode, applied",
                    this.id,
                    current,
                    next,
                    reason);
        } else {
            STATE_LOG.debug("vmcluster {} state: {} → {} (reason={})", this.id, current, next, reason);
        }
        this.provisioningStatus = next;

        // Audit history — Spring bean 가용 시 row insert (REQUIRES_NEW TX 이므로 caller 비즈니스
        // TX rollback 과 무관). null tolerant — unit test / startup 초기 같이 context wire 전 호출
        // 시 silent skip.
        Object recorder = com.aipaas.anycloud.common.util.SpringBeanHolder.beanOrNull(
                com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRecorder.class);
        if (recorder instanceof com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRecorder r) {
            r.record(this, current, next, reason);
        }
    }
}
