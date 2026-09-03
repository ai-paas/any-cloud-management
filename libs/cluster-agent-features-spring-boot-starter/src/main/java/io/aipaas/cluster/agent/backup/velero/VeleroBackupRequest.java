package io.aipaas.cluster.agent.backup.velero;

import java.time.Duration;
import java.util.List;

/**
 * Velero Backup CR 생성 요청.
 *
 * <p>Velero docs: https://velero.io/docs/main/api-types/backup/
 *
 * @param name                CR name. cluster 안에서 유니크. caller 가 충돌 방지 (보통 timestamp 포함).
 * @param namespace           Velero 가 동작하는 ns. default "velero".
 * @param includedNamespaces  백업할 K8s namespace 목록. 비어있으면 모든 namespace.
 * @param excludedNamespaces  제외할 namespace.
 * @param includedResources   백업할 resource kind (예: ["deployments", "configmaps"]). 비어있으면 모두.
 * @param ttl                 백업 보존 기간. null 이면 Velero default (720h = 30일).
 * @param snapshotVolumes     PV CSI snapshot 포함 여부.
 * @param storageLocation     BSL 이름. 비어있으면 "default".
 * @param labelSelector       특정 label 만 백업 (key=value 형식). 비어있으면 모두.
 */
public record VeleroBackupRequest(
		String name,
		String namespace,
		List<String> includedNamespaces,
		List<String> excludedNamespaces,
		List<String> includedResources,
		Duration ttl,
		boolean snapshotVolumes,
		String storageLocation,
		String labelSelector) {

	public static VeleroBackupRequest fullCluster(String name) {
		return new VeleroBackupRequest(
				name, "velero",
				List.of(), List.of(), List.of(),
				Duration.ofHours(720), true,
				"default", null);
	}

	public static VeleroBackupRequest namespaces(String name, List<String> namespaces) {
		return new VeleroBackupRequest(
				name, "velero",
				namespaces, List.of(), List.of(),
				Duration.ofHours(720), true,
				"default", null);
	}
}
