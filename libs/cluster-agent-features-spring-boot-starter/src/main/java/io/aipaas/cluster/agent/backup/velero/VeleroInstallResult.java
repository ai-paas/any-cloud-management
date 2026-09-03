package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero helm install 결과.
 *
 * @param clusterName 대상 cluster
 * @param releaseName helm release name (보통 "velero")
 * @param namespace   설치 namespace (보통 "velero")
 * @param chartVersion 실제 적용된 차트 버전
 * @param status      helm status — "deployed" / "failed" 등
 */
public record VeleroInstallResult(
		String clusterName,
		String releaseName,
		String namespace,
		String chartVersion,
		String status) {}
