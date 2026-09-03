package io.aipaas.cluster.agent.runtime;

import java.util.List;

/**
 * Single-kind resolution 결과. {@link ResourceKindInfo} 와 같은 schema 지만 single-resolution 의
 * 의미 단위라 별도 record (의도 표시 — caller 가 "list" vs "resolve" 명확히 구분).
 *
 * <p>Agent 의 {@code RESOLVE_RESOURCE} command 응답 OK 시 채워짐.
 *
 * @see KubeResourceService#resolveResource(String, String)
 */
public record ResolvedResource(
		String plural,
		String singular,
		String kind,
		String group,
		String version,
		boolean namespaced,
		List<String> shortNames) {
}
