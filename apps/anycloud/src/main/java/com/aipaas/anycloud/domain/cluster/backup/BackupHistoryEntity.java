package com.aipaas.anycloud.domain.cluster.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * etcd / PKI / Velero backup 의 영구 기록.  *
 * <p>cluster-backup-starter 의 {@link io.aipaas.cluster.agent.backup.port.BackupHistoryWriter}
 * SPI 를 통해 starter 의 backup service 가 backup 시작/완료 시점에 본 row 를 갱신한다.
 * adapter: {@link com.aipaas.anycloud.domain.cluster.backup.JpaBackupHistoryWriter}.
 *
 * <p>설계 결정:
 * <ul>
 *   <li>backup_type 은 enum 대신 VARCHAR — 향후 새 backup 종류 확장 부담 없음.</li>
 *   <li>storage_uri 만 기록 — binary blob 은 외부 (RustFS/S3/Velero backup store) 저장.</li>
 *   <li>requested_by — impersonation user. system action (scheduled) 은 NULL.</li>
 *   <li>같은 cluster + type 의 history 가 row 다수로 누적 — 재시도/이력 보존.</li>
 * </ul>
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "backup_history")
public class BackupHistoryEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "cluster_id", length = 45, nullable = false)
    private String clusterId;

    /** ETCD / PKI / VELERO_FULL / VELERO_NAMESPACE / VELERO_SELECTOR — enum-free for extensibility. */
    @Column(name = "backup_type", length = 32, nullable = false)
    private String backupType;

    /** Velero CR metadata.name (type=VELERO_* 만). type=ETCD/PKI 는 null. */
    @Column(name = "velero_backup_name", length = 253)
    private String veleroBackupName;

    /** STARTED / SUCCEEDED / FAILED — lifecycle state. */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** STARTED 시점엔 null. SUCCEEDED 시 채워짐. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_uri", length = 512)
    private String storageUri;

    /** OIDC user. system / scheduled action 은 null. */
    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
