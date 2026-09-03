package io.aipaas.cluster.agent.observability.alerts;

/**
 * AlertRuleInstaller 의 install/uninstall 결과.
 *
 * @param clusterName cluster
 * @param ruleSetId   {@link AlertRuleSet#id()}
 * @param namespace   적용 namespace (보통 monitoring)
 * @param resourceName 적용된 PrometheusRule 의 metadata.name
 * @param appliedCount applied[] 길이 (install 시) / 0 (uninstall)
 * @param status      "applied" | "deleted" | "noop"
 */
public record AlertRuleApplyResult(
		String clusterName,
		String ruleSetId,
		String namespace,
		String resourceName,
		int appliedCount,
		String status) {
}
