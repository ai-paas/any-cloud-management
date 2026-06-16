package io.aipaas.cluster.agent.backup.velero.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyApplyResult;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyCatalog;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * BackupPolicyInstallerImpl 의 install / installAll / uninstall dispatch 회귀.
 *
 * <p>실제 catalog (3개 bundled policy) 위에서 mock registry 로 RPC 격리 — placeholder 치환된 manifest
 * 가 APPLY_MANIFEST 로, uninstall 이 DELETE_RESOURCE 로 dispatch 되는지 확인.
 */
class BackupPolicyInstallerImplTest {

	private AgentSessionRegistry registry;
	private BackupPolicyCatalog catalog;
	private BackupPolicyInstallerImpl installer;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		catalog = new BackupPolicyCatalog();
		installer = new BackupPolicyInstallerImpl(registry, catalog);
	}

	private void stubOk() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder()
								.setStatus(Status.OK)
								.setResult(Struct.newBuilder().build())
								.build()));
	}

	@Test
	void install_knownPolicy_dispatchesApplyManifest() {
		stubOk();

		BackupPolicyApplyResult result = installer.install("c1", "daily-full-cluster", "velero");

		assertThat(result.clusterName()).isEqualTo("c1");
		assertThat(result.policyId()).isEqualTo("daily-full-cluster");
		assertThat(result.status()).isEqualTo("applied");
		assertThat(result.resourceName()).isEqualTo("anycloud-daily-full-cluster");
		verify(registry, times(1)).sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt());
	}

	@Test
	void install_substitutesNamespacePlaceholder() {
		stubOk();
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		installer.install("c1", "daily-full-cluster", "velero-prod");

		verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		ControlMessage msg = captor.getValue().build();
		String manifest = msg.getCommand().getParams().getFieldsOrThrow("manifest").getStringValue();
		assertThat(manifest)
				.contains("namespace: velero-prod")
				.doesNotContain("${NAMESPACE}");
	}

	@Test
	void install_unknownPolicy_throwsInvalidParams() {
		assertThatThrownBy(() -> installer.install("c1", "no-such-policy", "velero"))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("INVALID_PARAMS"));
	}

	@Test
	void install_blankNamespace_usesDefaultVelero() {
		stubOk();
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		BackupPolicyApplyResult result = installer.install("c1", "daily-full-cluster", "  ");

		assertThat(result.namespace()).isEqualTo("velero");
		verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		String ns = captor.getValue().build().getCommand().getParams()
				.getFieldsOrThrow("namespace").getStringValue();
		assertThat(ns).isEqualTo("velero");
	}

	@Test
	void install_noActiveSession_mapsToNoActiveAgent() {
		// ExecutionException unwrap 후 NoActiveSessionException 이 cause 일 때
		// errorCode=NO_ACTIVE_AGENT 로 정확히 매핑되는지 검증.
		CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
		failed.completeExceptionally(new NoActiveSessionException("no session"));
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(failed);

		assertThatThrownBy(() -> installer.install("c1", "daily-full-cluster", "velero"))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("NO_ACTIVE_AGENT"));
	}

	@Test
	void installAll_iteratesCatalog_partialFailureCaptured() {
		// 첫 번째 호출은 OK, 두 번째부터 실패 — installAll 은 각 결과를 모아 반환 (예외 swallow).
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.OK)
								.setResult(Struct.newBuilder().build()).build()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.FAILED)
								.setErrorCode("APPLY_FAILED")
								.setErrorMessage("webhook timeout").build()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.OK)
								.setResult(Struct.newBuilder().build()).build()));

		List<BackupPolicyApplyResult> results = installer.installAll("c1", "velero");

		assertThat(results).hasSize(3);
		long applied = results.stream().filter(r -> "applied".equals(r.status())).count();
		long failed = results.stream().filter(r -> r.status().startsWith("failed")).count();
		assertThat(applied).isEqualTo(2);
		assertThat(failed).isEqualTo(1);
	}

	@Test
	void uninstall_dispatchesDeleteSchedule() {
		stubOk();
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		BackupPolicyApplyResult result = installer.uninstall("c1", "daily-full-cluster", "velero");

		assertThat(result.status()).isEqualTo("deleted");
		assertThat(result.resourceName()).isEqualTo("anycloud-daily-full-cluster");
		verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		Struct params = captor.getValue().build().getCommand().getParams();
		assertThat(params.getFieldsOrThrow("kind").getStringValue()).isEqualTo("Schedule");
		assertThat(params.getFieldsOrThrow("name").getStringValue()).isEqualTo("anycloud-daily-full-cluster");
	}
}
