package io.aipaas.cluster.agent.observability.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * PromQL vector/scalar 응답의 1개 항목 — typed.
 * @param value     숫자 값 (parse 실패 시 {@code Double.NaN})
 * @param labels    metric labels (e.g. node/namespace/pod)
 * @param timestamp 측정 시각 (PromQL 응답의 [unix, value] 쌍 중 unix)
 */
public record MetricSample(double value, Map<String, String> labels, Instant timestamp) {

	public MetricSample {
		labels = labels == null ? Map.of() : Map.copyOf(labels);
	}

	/** vector 결과의 단일 sample 인 경우 label 값으로 group 식별. */
	public String label(String key) {
		return labels.get(key);
	}
}
