package io.aipaas.cluster.agent.backup.node;

/**
 * etcd / PKI 백업 결과.
 *
 * <p>caller (host application) 가 {@link #payload()} 를 storage (S3 / GCS / NFS / KEK 암호화 후 등)
 * 에 올린다. starter 는 storage 에 직접 쓰지 않음 — credential / retention 은 호스트 책임.
 *
 * @param clusterName 대상 cluster
 * @param nodeName    백업이 수행된 control-plane 노드 (cluster-agent 가 자동 선택)
 * @param payload     raw 백업 bytes (etcd snapshot 또는 pki.tar.gz). storage 에 그대로 저장 가능.
 * @param sizeBytes   payload.length — 호환 + sanity check
 * @param sha256Hex   payload 의 SHA-256 hex (cluster-agent 가 검증 후 전달). caller 가 storage 후 재검증
 *                    하면 end-to-end 무결성 확보.
 * @param metadata   "etcd_version=3.5.10" / "file_count=42,uncompressed_size=3145728" 등
 */
public record BackupResult(
		String clusterName,
		String nodeName,
		byte[] payload,
		long sizeBytes,
		String sha256Hex,
		String metadata) {

	/** 짧은 toString — 큰 payload 가 log 에 dump 되는 사고 방지. */
	@Override
	public String toString() {
		return "BackupResult{cluster=" + clusterName + ", node=" + nodeName
				+ ", size=" + sizeBytes + ", sha256=" + sha256Hex
				+ ", metadata='" + metadata + "'}";
	}
}
