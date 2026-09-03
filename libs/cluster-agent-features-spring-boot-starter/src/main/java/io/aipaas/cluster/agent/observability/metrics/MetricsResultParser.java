package io.aipaas.cluster.agent.observability.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * PromQL JSON 응답 → typed {@link MetricSample} list 변환.
 *
 * <p>Prometheus /api/v1/query 응답 구조:
 * <pre>
 *   {"status":"success","data":{"resultType":"vector",
 *     "result":[{"metric":{"node":"n1"},"value":[1700000000,"0.42"]}, ...]}}
 * </pre>
 * resultType: vector / matrix / scalar / string 지원.
 * matrix 는 각 시계열의 마지막 sample 만 추출 (instant snapshot 의도 한정).
 */
public final class MetricsResultParser {

	private MetricsResultParser() {}

	/** PromQL 응답을 sample list 로. 빈 결과면 빈 list. */
	public static List<MetricSample> parse(PromQLResult result, ObjectMapper mapper) {
		if (result == null || result.raw() == null || result.raw().isBlank()) {
			return List.of();
		}
		JsonNode root;
		try {
			root = mapper.readTree(result.raw());
		} catch (Exception e) {
			throw new ObservabilityException("PROMQL_PARSE_FAILED",
					"failed to parse PromQL JSON: " + e.getMessage(), e);
		}
		if (!"success".equals(root.path("status").asText())) {
			throw new ObservabilityException("PROMQL_STATUS_NOT_SUCCESS",
					"PromQL returned status=" + root.path("status").asText("?"));
		}
		JsonNode data = root.path("data");
		String resultType = data.path("resultType").asText();
		JsonNode resultArr = data.path("result");

		return switch (resultType) {
			case "vector" -> parseVector(resultArr);
			case "matrix" -> parseMatrixLastSample(resultArr);
			case "scalar" -> List.of(parseScalarOrString(resultArr));
			case "string" -> List.of(parseScalarOrString(resultArr));
			default -> List.of();
		};
	}

	private static List<MetricSample> parseVector(JsonNode arr) {
		List<MetricSample> out = new ArrayList<>();
		if (!arr.isArray()) return out;
		for (JsonNode entry : arr) {
			Map<String, String> labels = readLabels(entry.path("metric"));
			JsonNode value = entry.path("value");
			if (value.isArray() && value.size() == 2) {
				double v = parseDouble(value.get(1).asText());
				Instant ts = Instant.ofEpochSecond(value.get(0).asLong());
				out.add(new MetricSample(v, labels, ts));
			}
		}
		return out;
	}

	/** matrix 의 마지막 sample 만 추출 — instant 의도. range 전체가 필요하면 별도 path. */
	private static List<MetricSample> parseMatrixLastSample(JsonNode arr) {
		List<MetricSample> out = new ArrayList<>();
		if (!arr.isArray()) return out;
		for (JsonNode entry : arr) {
			Map<String, String> labels = readLabels(entry.path("metric"));
			JsonNode values = entry.path("values");
			if (values.isArray() && values.size() > 0) {
				JsonNode last = values.get(values.size() - 1);
				if (last.isArray() && last.size() == 2) {
					double v = parseDouble(last.get(1).asText());
					Instant ts = Instant.ofEpochSecond(last.get(0).asLong());
					out.add(new MetricSample(v, labels, ts));
				}
			}
		}
		return out;
	}

	private static MetricSample parseScalarOrString(JsonNode arr) {
		if (arr.isArray() && arr.size() == 2) {
			double v = parseDouble(arr.get(1).asText());
			Instant ts = Instant.ofEpochSecond(arr.get(0).asLong());
			return new MetricSample(v, Map.of(), ts);
		}
		return new MetricSample(Double.NaN, Map.of(), Instant.EPOCH);
	}

	private static Map<String, String> readLabels(JsonNode metricNode) {
		Map<String, String> labels = new HashMap<>();
		Iterator<String> it = metricNode.fieldNames();
		while (it.hasNext()) {
			String k = it.next();
			labels.put(k, metricNode.get(k).asText());
		}
		return labels;
	}

	private static double parseDouble(String s) {
		if (s == null || s.isBlank() || "NaN".equals(s)) return Double.NaN;
		if ("+Inf".equals(s)) return Double.POSITIVE_INFINITY;
		if ("-Inf".equals(s)) return Double.NEGATIVE_INFINITY;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
