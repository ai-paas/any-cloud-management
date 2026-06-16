package com.aipaas.anycloud.domain.agent.upgrade;

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

/**
 * Fleet upgrade run — 운영자가 한 번 trigger 한 fleet-wide agent upgrade 의 진행 상태.
 *
 * <p>운영자가 {@code POST /v1/fleet/upgrade} 호출 시 row 생성 (status=PLANNED). Background
 * {@code FleetUpgradeScheduler} 가 PLANNED row 발견 → RUNNING 전환 → wave 순차 처리.
 *
 * <p>HA replica 의 backend pod 중 한 노드만 처리하도록 ShedLock 사용.
 */
@Entity
@Table(name = "fleet_upgrade_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FleetUpgradeRunEntity {

    @Id
    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Column(name = "target_image", length = 256, nullable = false)
    private String targetImage;

    /** 처리할 wave CSV (예: "CANARY,STAGING,GENERAL"). PAUSED 는 명시 안 됨 — orchestrator 가 skip. */
    @Column(name = "waves_csv", length = 128, nullable = false)
    private String wavesCsv;

    /** RUNNING 일 때의 진행 wave. PLANNED/COMPLETED/ABORTED 면 null 가능. */
    @Column(name = "current_wave", length = 16)
    private String currentWave;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    @Builder.Default
    private FleetUpgradeRunStatus status = FleetUpgradeRunStatus.PLANNED;

    @Column(name = "concurrency", nullable = false)
    @Builder.Default
    private int concurrency = 5;

    @Column(name = "failure_threshold", nullable = false)
    @Builder.Default
    private int failureThreshold = 20;

    @Column(name = "total_clusters", nullable = false)
    @Builder.Default
    private int totalClusters = 0;

    @Column(name = "succeeded_count", nullable = false)
    @Builder.Default
    private int succeededCount = 0;

    @Column(name = "failed_count", nullable = false)
    @Builder.Default
    private int failedCount = 0;

    @Column(name = "skipped_count", nullable = false)
    @Builder.Default
    private int skippedCount = 0;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
