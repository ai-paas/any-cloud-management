package io.aipaas.cluster.agent.observability.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * M2 ObservabilityQueryService 의 extraParams passthrough 회귀.
 *
 * <p>raw query endpoint 가 frontend 의 query string 을 그대로 forward — Prometheus 의 timeout /
 * limit / lookback_delta / stats 등 optional param 이 backend 를 거치며 누락 없이 agent param Struct
 * 에 채워지는지 격리 검증.
 */
class ObservabilityQueryServiceExtraParamsTest {

	private AgentSessionRegistry registry;
	private ObservabilityQueryService svc;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		ClusterCatalog catalog = Mockito.mock(ClusterCatalog.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		svc = new ObservabilityQueryService(registry, catalog, Duration.ofSeconds(2));
		stubOk();
	}

	private void stubOk() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder()
								.setStatus(Status.OK)
								.setResult(Struct.newBuilder()
										.putFields("raw", Value.newBuilder().setStringValue("{}").build())
										.build())
								.build()));
	}

	private Struct captureSendParams() {
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);
		Mockito.verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		return captor.getValue().build().getCommand().getParams();
	}

	@Test
	void queryInstant_extraParamsAppended() {
		Map<String, String> extras = Map.of("timeout", "10s", "limit", "500");

		svc.queryInstant("c1", "up", "1234567890", Duration.ofSeconds(2), extras);

		Struct params = captureSendParams();
		assertThat(params.getFieldsOrThrow("query").getStringValue()).isEqualTo("up");
		assertThat(params.getFieldsOrThrow("time").getStringValue()).isEqualTo("1234567890");
		assertThat(params.getFieldsOrThrow("timeout").getStringValue()).isEqualTo("10s");
		assertThat(params.getFieldsOrThrow("limit").getStringValue()).isEqualTo("500");
	}

	@Test
	void queryInstant_blankExtraValueSkipped() {
		// passthrough 는 non-blank 만 — Prometheus default 값 보존 의도.
		Map<String, String> extras = new HashMap<>();
		extras.put("timeout", "10s");
		extras.put("lookback_delta", "  ");                 // blank — skip.

		svc.queryInstant("c1", "up", null, Duration.ofSeconds(2), extras);

		Struct params = captureSendParams();
		assertThat(params.getFieldsMap()).containsKey("timeout");
		assertThat(params.getFieldsMap()).doesNotContainKey("lookback_delta");
	}

	@Test
	void queryInstant_nullExtras_omittedWithoutCrash() {
		svc.queryInstant("c1", "up", null, Duration.ofSeconds(2), null);

		Struct params = captureSendParams();
		// 본문 param 은 있고 extras 는 안 들어감.
		assertThat(params.getFieldsMap()).containsKeys("query", "time");
		assertThat(params.getFieldsMap()).doesNotContainKeys("timeout", "limit", "stats");
	}

	@Test
	void queryRange_extraParamsAppended() {
		Map<String, String> extras = Map.of("stats", "all");

		svc.queryRange("c1", "up", "0", "100", "10", Duration.ofSeconds(2), extras);

		Struct params = captureSendParams();
		assertThat(params.getFieldsOrThrow("start").getStringValue()).isEqualTo("0");
		assertThat(params.getFieldsOrThrow("end").getStringValue()).isEqualTo("100");
		assertThat(params.getFieldsOrThrow("step").getStringValue()).isEqualTo("10");
		assertThat(params.getFieldsOrThrow("stats").getStringValue()).isEqualTo("all");
	}

	@Test
	void queryInstant_nullKeyInExtras_skipped() {
		// LinkedHashMap 은 null 키 허용 — defensive check.
		Map<String, String> extras = new HashMap<>();
		extras.put(null, "junk");
		extras.put("timeout", "5s");

		svc.queryInstant("c1", "up", null, Duration.ofSeconds(2), extras);

		Struct params = captureSendParams();
		assertThat(params.getFieldsOrThrow("timeout").getStringValue()).isEqualTo("5s");
	}
}
