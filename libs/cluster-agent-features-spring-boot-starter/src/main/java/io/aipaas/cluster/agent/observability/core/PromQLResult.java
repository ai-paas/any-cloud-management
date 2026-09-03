package io.aipaas.cluster.agent.observability.core;

/**
 * Prometheus /api/v1/query 응답의 starter-side 표현.
 *
 * <p>Agent 가 cluster 안 Prometheus 에서 받은 raw JSON 을 그대로 {@code raw} 필드에 담아 전송.
 * Caller (REST controller 등) 는 본 record 의 raw 를 Frontend 로 그대로 forward 하거나 별도 Jackson
 * 파싱이 가능 — 모든 PromQL 응답 형식 (matrix/vector/scalar/string) 호환.
 *
 * @param clusterName    쿼리 대상 cluster.
 * @param prometheusUrl  agent 가 사용한 Prometheus base URL (in-cluster DNS 또는 override).
 * @param isRange        true 면 query_range, false 면 instant query.
 * @param raw            Prometheus 응답의 JSON body 그대로. {@code {"status":"success","data":{...}}}.
 */
public record PromQLResult(
		String clusterName,
		String prometheusUrl,
		boolean isRange,
		String raw) {}
