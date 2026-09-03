package io.aipaas.cluster.agent.observability.metrics;

/**
 * Starter 가 정의한 표준 PromQL 카탈로그 항목 — 호스트가 사용자에게 노출하거나 직접 편집 baseline 으로 활용.
 *
 * @param id           안정적 식별자 (snake_case, e.g. "node_cpu_usage")
 * @param label        사람용 라벨 (e.g. "Node 별 CPU 사용률")
 * @param description  의미 / 단위 (e.g. "0~1 비율, idle 제외")
 * @param promql       PromQL 문자열. window 자리에는 placeholder `{{window}}` 사용 (e.g. "5m").
 *                     window 가 필요 없으면 placeholder 도 없음.
 * @param hasWindow    {@code promql} 안에 {@code {{window}}} placeholder 가 있는지.
 */
public record StandardQuery(
		String id,
		String label,
		String description,
		String promql,
		boolean hasWindow) {

	/** placeholder 치환 — hasWindow=false 면 원본 그대로. */
	public String render(String window) {
		if (!hasWindow) return promql;
		return promql.replace("{{window}}", window == null || window.isBlank() ? "5m" : window);
	}
}
