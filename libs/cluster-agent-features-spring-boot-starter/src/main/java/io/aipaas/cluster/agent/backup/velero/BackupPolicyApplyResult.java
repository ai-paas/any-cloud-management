package io.aipaas.cluster.agent.backup.velero;

/**
 * Policy install/uninstall 결과.
 *
 * @param clusterName 대상 cluster
 * @param policyId    {@link BackupPolicy#id()}
 * @param namespace   적용 namespace (보통 "velero")
 * @param resourceName 적용된 Velero Schedule 의 metadata.name (예: "anycloud-daily-full-cluster")
 * @param status      "applied" | "deleted" | "failed: <code>"
 */
public record BackupPolicyApplyResult(
		String clusterName,
		String policyId,
		String namespace,
		String resourceName,
		String status) {}
