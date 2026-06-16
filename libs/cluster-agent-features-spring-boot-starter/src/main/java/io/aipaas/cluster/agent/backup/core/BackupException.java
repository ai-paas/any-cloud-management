package io.aipaas.cluster.agent.backup.core;

/**
 * cluster-backup starter 의 검사된 오류.
 *
 * <p>error code 컨벤션 (observability-starter 의 ObservabilityException 과 동일 패턴):
 * <ul>
 *   <li>NO_ACTIVE_AGENT — agent stream 없음 (SERVICE_UNAVAILABLE)</li>
 *   <li>TIMEOUT — agent 응답 timeout (GATEWAY_TIMEOUT)</li>
 *   <li>NO_NODE_AGENT — node-agent DaemonSet 미설치</li>
 *   <li>UPGRADE_PLAN_FAILED / UPGRADE_APPLY_FAILED — kubeadm 실행 실패</li>
 *   <li>DRAIN_FAILED — PDB 등으로 노드 drain 실패</li>
 *   <li>BACKUP_FAILED / RESTORE_FAILED — etcd/PKI 작업 실패</li>
 *   <li>VELERO_NOT_INSTALLED / VELERO_API_ERROR — Velero CR 작업 실패</li>
 *   <li>INVALID_PARAMS — caller 가 잘못된 입력</li>
 *   <li>AGENT_CALL_FAILED — 기타 RPC 실패</li>
 * </ul>
 */
public class BackupException extends RuntimeException {

	private final String errorCode;

	public BackupException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public BackupException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
