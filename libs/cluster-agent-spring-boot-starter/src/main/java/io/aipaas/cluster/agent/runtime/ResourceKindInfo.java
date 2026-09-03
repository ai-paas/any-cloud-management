package io.aipaas.cluster.agent.runtime;

import java.util.List;

/**
 * Cluster discovery API 가 노출하는 단일 resource (kind) 의 정규화된 view.
 *
 * <p>Agent 의 {@code LIST_RESOURCE_KINDS} 응답을 도메인 record 로 1:1 변환한 형태.
 * UI 의 "kind picker" / "resource browser" 의 데이터 소스로 사용.
 *
 * @param plural     plural resource name — kubectl 의 PATH 와 일치 (e.g. "pods", "storageclasses")
 * @param singular   singular name — agent 가 empty 면 빈 문자열
 * @param kind       PascalCase kind — manifest 의 {@code kind:} 필드 (e.g. "Pod", "StorageClass")
 * @param group      API group — core 자원이면 빈 문자열 (e.g. "apps", "storage.k8s.io")
 * @param version    API version (e.g. "v1")
 * @param namespaced true 면 namespace-scoped — 호출 시 namespace 필수
 * @param shortNames kubectl short alias 목록 — 없으면 빈 list (never null)
 */
public record ResourceKindInfo(
		String plural,
		String singular,
		String kind,
		String group,
		String version,
		boolean namespaced,
		List<String> shortNames) {
}
