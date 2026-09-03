package io.aipaas.cluster.agent.observability.dashboard;

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
import io.aipaas.cluster.agent.observability.core.DashboardLocation;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/** Grafana URL 조회 결과 매핑 + 미노출 분기. */
class DashboardLocatorTest {

	private AgentSessionRegistry registry;
	private DashboardLocator locator;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		locator = new DashboardLocator(registry, Duration.ofMillis(500));
	}

	@Test
	void locate_loadBalancerExposed_returnsExternalUrl() {
		stubResponse("c1", CommandResponse.newBuilder()
				.setStatus(Status.OK)
				.setResult(Struct.newBuilder()
						.putFields("url", strVal("http://grafana.example.com:3000"))
						.putFields("host", strVal("grafana.example.com"))
						.putFields("port", numVal(3000))
						.putFields("exposure", strVal("LoadBalancer"))
						.build())
				.build());

		DashboardLocation r = locator.locate("c1", "monitoring", null);

		assertThat(r.clusterName()).isEqualTo("c1");
		assertThat(r.url()).isEqualTo("http://grafana.example.com:3000");
		assertThat(r.exposure()).isEqualTo("LoadBalancer");
		assertThat(r.port()).isEqualTo(3000);
	}

	@Test
	void locate_ingressExposed_returnsHostOnUrl() {
		stubResponse("c1", CommandResponse.newBuilder()
				.setStatus(Status.OK)
				.setResult(Struct.newBuilder()
						.putFields("url", strVal("http://grafana.demo.example.com"))
						.putFields("host", strVal("grafana.demo.example.com"))
						.putFields("port", numVal(80))
						.putFields("exposure", strVal("Ingress"))
						.build())
				.build());

		DashboardLocation r = locator.locate("c1", null, null);

		assertThat(r.exposure()).isEqualTo("Ingress");
		assertThat(r.port()).isEqualTo(80);
	}

	@Test
	void locate_grafanaNotExposed_throwsGRAFANA_NOT_EXPOSED() {
		stubResponse("c1", CommandResponse.newBuilder()
				.setStatus(Status.FAILED)
				.setErrorCode("GRAFANA_NOT_EXPOSED")
				.setErrorMessage("Grafana only reachable via in-cluster DNS")
				.build());

		assertThatThrownBy(() -> locator.locate("c1", null, null))
				.isInstanceOf(ObservabilityException.class)
				.satisfies(ex -> assertThat(((ObservabilityException) ex).errorCode())
						.isEqualTo("GRAFANA_NOT_EXPOSED"));
	}

	@Test
	void locate_serviceNotFound_propagatesErrorCode() {
		stubResponse("c1", CommandResponse.newBuilder()
				.setStatus(Status.FAILED)
				.setErrorCode("GRAFANA_SERVICE_NOT_FOUND")
				.setErrorMessage("service monitoring/kube-prometheus-stack-grafana: not found")
				.build());

		assertThatThrownBy(() -> locator.locate("c1", null, null))
				.satisfies(ex -> assertThat(((ObservabilityException) ex).errorCode())
						.isEqualTo("GRAFANA_SERVICE_NOT_FOUND"));
	}

	private void stubResponse(String clusterName, CommandResponse response) {
		when(registry.sendCommand(eq(clusterName), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(response));
	}

	private static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s).build();
	}

	private static Value numVal(double n) {
		return Value.newBuilder().setNumberValue(n).build();
	}
}
