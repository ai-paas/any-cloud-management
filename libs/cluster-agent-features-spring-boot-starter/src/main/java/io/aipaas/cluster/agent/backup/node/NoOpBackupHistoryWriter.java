package io.aipaas.cluster.agent.backup.node;
import io.aipaas.cluster.agent.backup.port.BackupHistoryWriter;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link BackupHistoryWriter} 의 NoOp default — host 가 자체 impl 등록 안 하면 사용.
 *
 * <p>start() 가 stable dummy id 반환 (caller 가 succeed/fail 호출 시 trace 식별 용). 실제 DB
 * 기록은 없음. log.debug 로 trace 만.
 *
 * <p>production 환경에서는 본 default 가 silent — host 가 DB-backed impl 안 등록하면 backup
 * history 가 사라진다는 의미. autoconfigure 의 default 로 등록되므로 startup 시 log.info 으로
 * "NoOpBackupHistoryWriter 활성 — production 에선 BackupHistoryWriter bean 등록 권장" 안내.
 */
@Slf4j
public class NoOpBackupHistoryWriter implements BackupHistoryWriter {

	@Override
	public String start(BackupStartRequest request) {
		log.debug("NoOpBackupHistoryWriter.start: cluster={} type={} (not persisted)",
				request.clusterId(), request.backupType());
		// id 는 caller 가 trace 에 매핑할 수 있게 stable 생성. UUID 사용 — host 가 자체 impl 시
		// 다른 id scheme 도 OK.
		return "noop-" + System.nanoTime();
	}

	@Override
	public void succeed(String historyId, BackupSuccessReport report) {
		log.debug("NoOpBackupHistoryWriter.succeed: id={} size={} uri={} (not persisted)",
				historyId, report.sizeBytes(), report.storageUri());
	}

	@Override
	public void fail(String historyId, String error) {
		log.debug("NoOpBackupHistoryWriter.fail: id={} error={} (not persisted)", historyId, error);
	}
}
