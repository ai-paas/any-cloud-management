package io.aipaas.cluster.agent.backup.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Backup 실행 결과를 host backend 의 영구 저장소 (DB / object storage / etc.) 에 기록하는 SPI.
 *
 * <p>Starter 는 본 interface 만 의존 — DB / JPA / Spring Data 등에 결합하지 않는다. host backend
 * (e.g. anycloud) 가 자체 구현 등록 → starter 의 EtcdBackupService / PkiBackupService / Velero
 * services 가 backup 호출 전후 본 SPI 로 history 기록.
 *
 * <p>구현이 없으면 ({@link io.aipaas.cluster.agent.backup.autoconfigure.ClusterAgentBackupAutoConfiguration}
 * 가 @ConditionalOnMissingBean 으로 NoOp default) backup 자체는 정상 수행 — history 만 미기록.
 * test / dev / library 단독 사용 시 옵션.
 *
 * <p>호출 시점:
 * <ol>
 *   <li>{@link #start(BackupStartRequest)} — backup 시작 직전. row 생성 + id 반환.</li>
 *   <li>{@link #succeed(String, BackupSuccessReport)} — 성공 시 size/uri/completed_at 기록.</li>
 *   <li>{@link #fail(String, String)} — 실패 시 error + completed_at 기록.</li>
 * </ol>
 */
public interface BackupHistoryWriter {

	/**
	 * Backup 시작 기록. row id 반환 (host backend 가 결정 — UUID 권장).
	 * 같은 cluster + backup_type 의 history 가 다수 row 로 누적되어도 OK (각자 별 id).
	 */
	String start(BackupStartRequest request);

	/** Backup 성공 기록. {@link #start} 가 반환한 id 로 같은 row 갱신. */
	void succeed(String historyId, BackupSuccessReport report);

	/** Backup 실패 기록. error 메시지 + completed_at 갱신. */
	void fail(String historyId, String error);

	/** History id 로 조회 (admin / UI 용). 미구현이면 {@link Optional#empty()} 가능. */
	default Optional<BackupRecord> findById(String historyId) {
		return Optional.empty();
	}

	// ---- DTOs ----

	/** Backup 시작 요청 payload — start() 의 input. */
	record BackupStartRequest(
			String clusterId,
			String backupType,           // ETCD / PKI / VELERO_FULL / VELERO_NAMESPACE / VELERO_SELECTOR
			String veleroBackupName,     // VELERO_* 만 채움 — type=ETCD/PKI 는 null
			String requestedBy) {        // impersonation user; system action 은 null
	}

	/** Backup 성공 보고 — succeed() 의 input. */
	record BackupSuccessReport(
			long sizeBytes,
			String storageUri,           // s3://... / file://... / velero-backup-store/...
			Instant completedAt) {
	}

	/** History row 조회 결과 — findById() 의 output. */
	record BackupRecord(
			String id,
			String clusterId,
			String backupType,
			String veleroBackupName,
			String status,               // STARTED / SUCCEEDED / FAILED
			Long sizeBytes,
			String storageUri,
			String requestedBy,
			String error,
			Instant startedAt,
			Instant completedAt) {
	}
}
