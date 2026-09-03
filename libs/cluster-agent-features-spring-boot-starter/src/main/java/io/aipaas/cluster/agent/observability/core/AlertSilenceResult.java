package io.aipaas.cluster.agent.observability.core;

/**
 * Alertmanager /api/v2/silences (POST) 응답.
 *
 * @param clusterName     호출 대상 cluster
 * @param alertmanagerUrl agent 가 실제 호출한 Alertmanager URL (debug / audit 용)
 * @param silenceId       Alertmanager 발급 UUID
 * @param raw             전체 응답 JSON (caller 가 추가 필드 필요 시)
 */
public record AlertSilenceResult(
		String clusterName,
		String alertmanagerUrl,
		String silenceId,
		String raw) {
}
