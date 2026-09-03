package io.aipaas.cluster.agent.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PromQL JSON → MetricSample 파싱 회귀. */
class MetricsResultParserTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private PromQLResult wrap(String raw) {
		return new PromQLResult("c1", "http://prom:9090", false, raw);
	}

	@Test
	void parse_vector_extractsLabelsAndValues() {
		String raw = """
				{"status":"success","data":{"resultType":"vector","result":[
				  {"metric":{"node":"n1"},"value":[1700000000,"0.42"]},
				  {"metric":{"node":"n2"},"value":[1700000000,"0.61"]}
				]}}""";

		List<MetricSample> samples = MetricsResultParser.parse(wrap(raw), mapper);

		assertThat(samples).hasSize(2);
		assertThat(samples.get(0).value()).isEqualTo(0.42);
		assertThat(samples.get(0).label("node")).isEqualTo("n1");
		assertThat(samples.get(1).value()).isEqualTo(0.61);
		assertThat(samples.get(1).label("node")).isEqualTo("n2");
	}

	@Test
	void parse_emptyVector_returnsEmptyList() {
		String raw = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
		assertThat(MetricsResultParser.parse(wrap(raw), mapper)).isEmpty();
	}

	@Test
	void parse_matrix_extractsOnlyLastSample() {
		// matrix = range query 결과. parser 는 마지막 시점만 추출 (instant 의도).
		String raw = """
				{"status":"success","data":{"resultType":"matrix","result":[
				  {"metric":{"node":"n1"},"values":[
				    [1700000000,"0.10"],[1700000060,"0.20"],[1700000120,"0.30"]
				  ]}
				]}}""";

		List<MetricSample> samples = MetricsResultParser.parse(wrap(raw), mapper);

		assertThat(samples).hasSize(1);
		assertThat(samples.get(0).value()).isEqualTo(0.30);
		assertThat(samples.get(0).timestamp().getEpochSecond()).isEqualTo(1700000120L);
	}

	@Test
	void parse_scalar_singleSample() {
		String raw = "{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1700000000,\"42\"]}}";
		List<MetricSample> samples = MetricsResultParser.parse(wrap(raw), mapper);
		assertThat(samples).hasSize(1);
		assertThat(samples.get(0).value()).isEqualTo(42.0);
		assertThat(samples.get(0).labels()).isEmpty();
	}

	@Test
	void parse_nanInfValues_handledGracefully() {
		String raw = """
				{"status":"success","data":{"resultType":"vector","result":[
				  {"metric":{"a":"x"},"value":[1700000000,"NaN"]},
				  {"metric":{"a":"y"},"value":[1700000000,"+Inf"]},
				  {"metric":{"a":"z"},"value":[1700000000,"-Inf"]}
				]}}""";

		List<MetricSample> samples = MetricsResultParser.parse(wrap(raw), mapper);

		assertThat(samples).hasSize(3);
		assertThat(samples.get(0).value()).isNaN();
		assertThat(samples.get(1).value()).isInfinite();
		assertThat(samples.get(2).value()).isInfinite();
	}

	@Test
	void parse_nonSuccessStatus_throws() {
		String raw = "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}";
		assertThatThrownBy(() -> MetricsResultParser.parse(wrap(raw), mapper))
				.isInstanceOf(ObservabilityException.class)
				.hasMessageContaining("status=error");
	}

	@Test
	void parse_malformedJson_throws() {
		assertThatThrownBy(() -> MetricsResultParser.parse(wrap("{ not json"), mapper))
				.isInstanceOf(ObservabilityException.class)
				.hasMessageContaining("parse");
	}

	@Test
	void parse_nullOrBlankRaw_returnsEmpty() {
		assertThat(MetricsResultParser.parse(wrap(null), mapper)).isEmpty();
		assertThat(MetricsResultParser.parse(wrap(""), mapper)).isEmpty();
	}
}
