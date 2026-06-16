package io.aipaas.cluster.agent.observability.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.observability.core.AlertsResult;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import io.aipaas.cluster.agent.observability.core.MetricTargetsResult;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * ObservabilityQueryService — agent dispatch / 에러 매핑 / multi-cluster fan-out 회귀.
 *
 * <p>모든 테스트는 AgentSessionRegistry 를 mock 으로 주입. 실제 gRPC stream 없음.
 */
class ObservabilityQueryServiceTest {

	private AgentSessionRegistry registry;
	private ClusterCatalog catalog;
	private ObservabilityQueryService svc;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		catalog = Mockito.mock(ClusterCatalog.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		svc = new ObservabilityQueryService(registry, catalog, Duration.ofSeconds(2));
	}

	// ===== queryInstant =====

	@Test
	void queryInstant_happyPath_returnsResult() {
		stubResponse("c1", okResponse(Map.of(
				"prometheus_url", "http://prom.monitoring.svc:9090",
				"raw", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\"}}",
				"is_range", false)));

		PromQLResult result = svc.queryInstant("c1", "up", null, Duration.ofSeconds(2));

		assertThat(result.clusterName()).isEqualTo("c1");
		assertThat(result.prometheusUrl()).contains("monitoring.svc");
		assertThat(result.raw()).contains("success");
		assertThat(result.isRange()).isFalse();
	}

	@Test
	void queryInstant_agentFailure_throwsObservabilityException() {
		stubResponse("c1", errorResponse("PROM_QUERY_FAILED", "Prometheus 500"));

		assertThatThrownBy(() -> svc.queryInstant("c1", "up", null, Duration.ofSeconds(2)))
				.isInstanceOf(ObservabilityException.class)
				.hasMessageContaining("Prometheus 500")
				.satisfies(ex -> assertThat(((ObservabilityException) ex).errorCode())
						.isEqualTo("PROM_QUERY_FAILED"));
	}

	@Test
	void queryInstant_noActiveSession_mapsToNO_ACTIVE_AGENT() {
		CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
		failed.completeExceptionally(new NoActiveSessionException("no session"));
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(failed);

		assertThatThrownBy(() -> svc.queryInstant("c1", "up", null, Duration.ofSeconds(2)))
				.isInstanceOf(ObservabilityException.class)
				.satisfies(ex -> assertThat(((ObservabilityException) ex).errorCode())
						.isEqualTo("NO_ACTIVE_AGENT"));
	}

	@Test
	void queryInstant_timeout_mapsToTIMEOUT() {
		// CompletableFuture 미완료 → get(timeout) 시 TimeoutException.
		CompletableFuture<CommandResponse> pending = new CompletableFuture<>();
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(pending);

		assertThatThrownBy(() -> svc.queryInstant("c1", "up", null, Duration.ofMillis(100)))
				.isInstanceOf(ObservabilityException.class)
				.satisfies(ex -> assertThat(((ObservabilityException) ex).errorCode())
						.isEqualTo("TIMEOUT"));
	}

	// ===== queryRange =====

	@Test
	void queryRange_isRangeFlagSetToTrue() {
		stubResponse("c1", okResponse(Map.of(
				"prometheus_url", "http://prom.monitoring.svc:9090",
				"raw", "{\"status\":\"success\"}",
				"is_range", true)));

		PromQLResult result = svc.queryRange("c1", "up", "100", "200", "10", null);

		assertThat(result.isRange()).isTrue();
	}

	// ===== queryAll (fan-out) =====

	@Test
	void queryAll_returnsSuccessfulClustersOnly_failuresLogged() {
		when(catalog.listClusterNames()).thenReturn(List.of("c1", "c2", "c3"));
		stubResponse("c1", okResponse(Map.of(
				"prometheus_url", "http://prom1.monitoring.svc:9090",
				"raw", "{\"data\":\"c1\"}",
				"is_range", false)));
		stubResponse("c2", errorResponse("PROM_QUERY_FAILED", "c2 prometheus down"));
		stubResponse("c3", okResponse(Map.of(
				"prometheus_url", "http://prom3.monitoring.svc:9090",
				"raw", "{\"data\":\"c3\"}",
				"is_range", false)));

		Map<String, PromQLResult> result = svc.queryAll("up", Duration.ofSeconds(2));

		// c2 는 실패해서 결과 map 에서 제외됨 — partial result 패턴.
		assertThat(result).containsKeys("c1", "c3");
		assertThat(result).doesNotContainKey("c2");
		assertThat(result.get("c1").raw()).contains("c1");
		assertThat(result.get("c3").raw()).contains("c3");
	}

	@Test
	void queryAll_emptyCatalog_returnsEmptyMap() {
		when(catalog.listClusterNames()).thenReturn(List.of());

		Map<String, PromQLResult> result = svc.queryAll("up", Duration.ofSeconds(2));

		assertThat(result).isEmpty();
	}

	// ===== listTargets / listAlerts =====

	@Test
	void listTargets_returnsResultWithRawJson() {
		stubResponse("c1", okResponse(Map.of(
				"prometheus_url", "http://prom.monitoring.svc:9090",
				"raw", "{\"data\":{\"activeTargets\":[]}}")));

		MetricTargetsResult r = svc.listTargets("c1", "active", Duration.ofSeconds(2));

		assertThat(r.clusterName()).isEqualTo("c1");
		assertThat(r.raw()).contains("activeTargets");
	}

	@Test
	void listAlerts_returnsResultWithRawJson() {
		stubResponse("c1", okResponse(Map.of(
				"alertmanager_url", "http://am.monitoring.svc:9093",
				"raw", "[]")));

		AlertsResult r = svc.listAlerts("c1", Duration.ofSeconds(2));

		assertThat(r.clusterName()).isEqualTo("c1");
		assertThat(r.alertmanagerUrl()).contains("9093");
		assertThat(r.raw()).isEqualTo("[]");
	}

	// ===== helpers =====

	private void stubResponse(String clusterName, CommandResponse response) {
		when(registry.sendCommand(eq(clusterName), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(response));
	}

	private static CommandResponse okResponse(Map<String, Object> fields) {
		Struct.Builder s = Struct.newBuilder();
		fields.forEach((k, v) -> {
			Value.Builder vb = Value.newBuilder();
			if (v instanceof Boolean b) {
				vb.setBoolValue(b);
			} else if (v instanceof Number n) {
				vb.setNumberValue(n.doubleValue());
			} else {
				vb.setStringValue(v == null ? "" : v.toString());
			}
			s.putFields(k, vb.build());
		});
		return CommandResponse.newBuilder()
				.setStatus(Status.OK)
				.setResult(s.build())
				.build();
	}

	private static CommandResponse errorResponse(String code, String message) {
		return CommandResponse.newBuilder()
				.setStatus(Status.FAILED)
				.setErrorCode(code)
				.setErrorMessage(message)
				.build();
	}
}
