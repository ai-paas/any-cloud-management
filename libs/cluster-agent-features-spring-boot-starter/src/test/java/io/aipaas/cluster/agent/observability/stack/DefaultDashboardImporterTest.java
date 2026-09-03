package io.aipaas.cluster.agent.observability.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DefaultDashboardImporterTest {

	private AgentSessionRegistry registry;
	private DefaultDashboardImporter importer;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class);
		importer = new DefaultDashboardImporter(registry, "monitoring");
	}

	@Test
	void importClusterOverview_sendsApplyManifestWithDashboardConfigMap() {
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.OK).build()));

		importer.importClusterOverview("c1");

		ArgumentCaptor<ControlMessage.Builder> captor =
				ArgumentCaptor.forClass(ControlMessage.Builder.class);
		verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());

		ControlMessage built = captor.getValue().build();
		assertThat(built.getCommand().getType().name()).isEqualTo("APPLY_MANIFEST");

		Struct params = built.getCommand().getParams();
		String manifest = params.getFieldsOrThrow("manifest").getStringValue();
		assertThat(manifest)
				.contains("kind: ConfigMap")
				.contains("aipaas-cluster-overview")
				.contains("grafana_dashboard")
				.contains("AIPaaS Cluster Overview");
	}

	@Test
	void importGpuOverview_sendsApplyManifestWithGpuPanels() {
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.OK).build()));

		importer.importGpuOverview("c1");

		ArgumentCaptor<ControlMessage.Builder> captor =
				ArgumentCaptor.forClass(ControlMessage.Builder.class);
		verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());

		String manifest = captor.getValue().build().getCommand()
				.getParams().getFieldsOrThrow("manifest").getStringValue();
		assertThat(manifest)
				.contains("aipaas-gpu-overview")
				.contains("DCGM_FI_DEV_GPU_UTIL");
	}

	@Test
	void importClusterOverview_agentReturnsError_swallows() {
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder()
								.setStatus(Status.FAILED)
								.setErrorCode("CHART_NOT_ALLOWED")
								.setErrorMessage("denied")
								.build()));

		// 예외 던지지 않아야 함 (auto-installer 의 best-effort 보호).
		importer.importClusterOverview("c1");

		verify(registry).sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt());
	}
}
