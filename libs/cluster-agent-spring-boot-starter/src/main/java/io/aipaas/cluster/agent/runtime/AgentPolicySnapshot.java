package io.aipaas.cluster.agent.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Agent 의 in-memory allowlist + resource_policy snapshot. {@code GET_AGENT_CONFIG} 응답을 도메인
 * record 로 정규화. ConfigMap 의 raw text 가 아니라 agent 가 *현재 적용한* 상태 — wildcard 파싱
 * 등 transform 이 이미 반영된 view.
 *
 * <p>운영자가 ConfigMap edit 후 watch reload 가 안 됐다면, raw ConfigMap 과 본 snapshot 사이의
 * 불일치 (예: raw 는 "*", agent 는 deny-all) 가 그대로 노출됨 — 진단에 결정적.
 *
 * @param allowedNamespaces       명시 namespace list (wildcard 면 빈 list + allowAllNamespaces=true)
 * @param allowAllNamespaces      true 면 모든 namespace 허용
 * @param allowedCommands         RPC 종류 list (e.g. "LIST_PODS")
 * @param allowedCharts           Helm chart rule list (e.g. "repo/name:1.0.0-2.0.0")
 * @param allowedExecNamespaces   PodExec 전용 namespace list
 * @param allowAllExecNamespaces  PodExec wildcard
 * @param resourcePolicy          resource_policy section (nil 이면 legacy 동작)
 * @param lastReloadAt            ConfigMap 마지막 reload 시각 (null = reload 안 됨)
 * @param configMapResourceVersion 마지막 reload 한 ConfigMap 의 resourceVersion
 */
public record AgentPolicySnapshot(
		List<String> allowedNamespaces,
		boolean allowAllNamespaces,
		List<String> allowedCommands,
		List<String> allowedCharts,
		List<String> allowedExecNamespaces,
		boolean allowAllExecNamespaces,
		ResourcePolicy resourcePolicy,
		Instant lastReloadAt,
		String configMapResourceVersion) {

	/** allow_all_discovered vs strict — 정책 적용 모드. mode 가 비면 nil policy (legacy). */
	public record ResourcePolicy(
			String mode,
			List<ResourceRule> deny,
			List<ResourceRule> allow) {
	}

	/** kind 또는 (kind, namespace) 페어. namespace 비면 모든 ns. */
	public record ResourceRule(String kind, String namespace) {
	}
}
