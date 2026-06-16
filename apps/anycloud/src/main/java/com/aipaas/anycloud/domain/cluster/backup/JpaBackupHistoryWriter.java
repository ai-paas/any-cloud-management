package com.aipaas.anycloud.domain.cluster.backup;

import io.aipaas.cluster.agent.backup.port.BackupHistoryWriter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BackupHistoryWriter} 의 JPA 기반 anycloud 구현체.
 *
 * <p>cluster-backup-starter 의 backup service (EtcdBackupServiceImpl / PkiBackupServiceImpl /
 * VeleroBackupServiceImpl) 가 backup 시작/완료 시점에 본 SPI 호출 → DB 영구 기록. starter 의 default
 * NoOp 가 본 bean 으로 자동 교체.
 *
 * <p>{@code @Transactional(REQUIRES_NEW)} — backup 호출 caller 의 tx 와 독립. backup 자체가 실패해도
 * history row 가 fail status 로 남아야 하므로 (audit-style).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpaBackupHistoryWriter implements BackupHistoryWriter {

    private final BackupHistoryRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String start(BackupStartRequest request) {
        String id = "backup-" + UUID.randomUUID();
        BackupHistoryEntity row = BackupHistoryEntity.builder()
                .id(id)
                .clusterId(request.clusterId())
                .backupType(request.backupType())
                .veleroBackupName(request.veleroBackupName())
                .status("STARTED")
                .requestedBy(request.requestedBy())
                .startedAt(LocalDateTime.now())
                .build();
        repository.save(row);
        log.info(
                "BackupHistory STARTED id={} cluster={} type={} requestedBy={}",
                id,
                request.clusterId(),
                request.backupType(),
                request.requestedBy());
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String historyId, BackupSuccessReport report) {
        repository
                .findById(historyId)
                .ifPresentOrElse(
                        row -> {
                            row.setStatus("SUCCEEDED");
                            row.setSizeBytes(report.sizeBytes());
                            row.setStorageUri(report.storageUri());
                            row.setCompletedAt(
                                    report.completedAt() == null
                                            ? LocalDateTime.now()
                                            : LocalDateTime.ofInstant(report.completedAt(), ZoneOffset.UTC));
                            repository.save(row);
                            log.info(
                                    "BackupHistory SUCCEEDED id={} size={} uri={}",
                                    historyId,
                                    report.sizeBytes(),
                                    report.storageUri());
                        },
                        () -> log.warn("BackupHistory.succeed: id={} not found — skipping update", historyId));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String historyId, String error) {
        repository
                .findById(historyId)
                .ifPresentOrElse(
                        row -> {
                            row.setStatus("FAILED");
                            row.setError(truncate(error));
                            row.setCompletedAt(LocalDateTime.now());
                            repository.save(row);
                            log.warn("BackupHistory FAILED id={} error={}", historyId, truncate(error));
                        },
                        () -> log.warn("BackupHistory.fail: id={} not found — skipping update", historyId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BackupRecord> findById(String historyId) {
        return repository.findById(historyId).map(this::toRecord);
    }

    private BackupRecord toRecord(BackupHistoryEntity row) {
        return new BackupRecord(
                row.getId(),
                row.getClusterId(),
                row.getBackupType(),
                row.getVeleroBackupName(),
                row.getStatus(),
                row.getSizeBytes(),
                row.getStorageUri(),
                row.getRequestedBy(),
                row.getError(),
                row.getStartedAt() == null ? null : row.getStartedAt().toInstant(ZoneOffset.UTC),
                row.getCompletedAt() == null ? null : row.getCompletedAt().toInstant(ZoneOffset.UTC));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        int max = 10_000; // TEXT column — DB 자체는 더 길지만 log/UI 친화 cap.
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
