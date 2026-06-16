package io.aipaas.cluster.agent.backup.node;

import java.util.List;

/**
 * Kubernetes PKI 백업 service.
 *
 * <p>cluster-agent 의 {@code BACKUP_PKI} command 통해 control-plane 노드의 {@code /etc/kubernetes/pki}
 * 를 tar+gzip 으로 묶어 bytes 반환.
 *
 * <p><b>보안</b>: PKI 는 cluster root key. caller (host) 가 storage 에 올리기 전 반드시 KEK 로 encrypt.
 * Vault / AWS KMS / GCP KMS 같은 외부 secret store 권장.
 */
public interface PkiBackupService {

	/** 전체 /etc/kubernetes/pki 백업. */
	default BackupResult backup(String clusterName) {
		return backup(clusterName, PkiBackupOptions.full());
	}

	BackupResult backup(String clusterName, PkiBackupOptions options);

	/**
	 * PKI backup 옵션.
	 *
	 * @param includePaths /etc/kubernetes/pki 기준 relative path 목록. 비어있으면 전체.
	 *                     예: ["ca.crt", "ca.key", "sa.key", "sa.pub"] — root CA + SA 키만 백업.
	 * @param chunkSize    streaming chunk size (bytes). 0 이면 1MB default.
	 */
	record PkiBackupOptions(
			List<String> includePaths,
			int chunkSize) {
		public static PkiBackupOptions full() {
			return new PkiBackupOptions(List.of(), 0);
		}

		public static PkiBackupOptions onlyCaAndSa() {
			return new PkiBackupOptions(List.of("ca.crt", "ca.key", "sa.key", "sa.pub"), 0);
		}
	}
}
