package io.aipaas.cluster.agent.backup.node;

/**
 * etcd snapshot 백업 service.
 *
 * <p>cluster-agent 의 {@code BACKUP_ETCD} command 를 통해 control-plane 노드 1개의 etcd 를 snapshot
 * → bytes 반환. 100MB PoC 한계 (true streaming proxy 로 확장 예정).
 */
public interface EtcdBackupService {

	/**
	 * 기본 옵션으로 etcd snapshot 생성 (kubeadm 기본 endpoint + 기본 cert 경로).
	 *
	 * @param clusterName 대상 cluster
	 * @return BackupResult — caller 가 storage 에 올림
	 */
	default BackupResult backup(String clusterName) {
		return backup(clusterName, EtcdBackupOptions.defaults());
	}

	/**
	 * 옵션 명시 backup. caller 가 별도 etcd endpoint / cert path / chunk size override 가능.
	 *
	 * @throws io.aipaas.cluster.agent.backup.core.BackupException 실패 시 (error code 로 분기).
	 */
	BackupResult backup(String clusterName, EtcdBackupOptions options);

	/**
	 * etcd backup 옵션. 모든 필드 optional — 비어있으면 agent 측 default 사용.
	 *
	 * @param endpoint       etcd URL (예: "https://10.0.0.1:2379"). null 이면 agent 가 https://127.0.0.1:2379.
	 * @param caCertPath     CA cert path (서버 측). null 이면 kubeadm 기본 (/etc/kubernetes/pki/etcd/ca.crt).
	 * @param clientCertPath client cert path. null 이면 kubeadm healthcheck-client.crt.
	 * @param clientKeyPath  client key path. 동일.
	 * @param chunkSize      streaming chunk size (bytes). 0 이면 1MB default.
	 */
	record EtcdBackupOptions(
			String endpoint,
			String caCertPath,
			String clientCertPath,
			String clientKeyPath,
			int chunkSize) {
		public static EtcdBackupOptions defaults() {
			return new EtcdBackupOptions(null, null, null, null, 0);
		}
	}
}
