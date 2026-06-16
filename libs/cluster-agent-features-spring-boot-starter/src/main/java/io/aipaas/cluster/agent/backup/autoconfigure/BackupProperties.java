package io.aipaas.cluster.agent.backup.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cluster-backup starter 의 동작 설정.
 *
 * <pre>{@code
 * cluster-backup:
 *   node:
 *     chunk-size: 4194304        # 4 MB streaming chunk
 *     encryption-enabled: false  # PKI 백업 암호화 (host KEK 필요)
 *   velero:
 *     auto-install: false
 *     chart-version: "8.2.0"
 *     namespace: "velero"
 *     default-ttl: "720h"
 *     auto-install-policies: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "cluster-backup")
public record BackupProperties(Node node, Velero velero) {

	public BackupProperties {
		if (node == null) {
			node = new Node(4 * 1024 * 1024, false);
		}
		if (velero == null) {
			velero = new Velero(false, "8.2.0", "velero", Duration.ofHours(720), true);
		}
	}

	/**
	 * node-agent 가 수행하는 etcd / PKI raw 백업 설정.
	 *
	 * @param chunkSize          gRPC server-streaming 의 chunk byte 크기. etcd snapshot 이 수 GB 까지 가능 — 4 MB chunk 권장.
	 * @param encryptionEnabled  PKI 백업 시 host KEK 로 암호화할지. true 이면 host 가 BackupEncryptor SPI 구현 필요.
	 */
	public record Node(int chunkSize, boolean encryptionEnabled) {}

	/**
	 * Velero 통합 설정.
	 *
	 * @param autoInstall           cluster ACTIVE 시 자동 Velero 설치 여부. 기본 false — 사용자 명시 활성 권장 (storage credential 필요).
	 * @param chartVersion          velero helm chart 버전.
	 * @param namespace             Velero 가 동작할 cluster namespace.
	 * @param defaultTtl            Backup CR 의 default TTL (만료 후 자동 삭제).
	 * @param autoInstallPolicies   bundled velero-policies/*.yaml 을 자동으로 Schedule CR 로 등록할지.
	 */
	public record Velero(
			boolean autoInstall,
			String chartVersion,
			String namespace,
			Duration defaultTtl,
			boolean autoInstallPolicies) {}
}
