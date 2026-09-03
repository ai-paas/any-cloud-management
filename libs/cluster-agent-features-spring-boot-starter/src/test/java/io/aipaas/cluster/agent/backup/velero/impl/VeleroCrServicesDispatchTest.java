package io.aipaas.cluster.agent.backup.velero.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroCrResult;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleRequest;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * Backup/Restore/Schedule service 의 agent dispatch + error mapping 회귀.
 *
 * <p>VeleroCrApplier 가 모든 CR 의 APPLY_MANIFEST 를 routing — 본 테스트는 그 entrypoint 의 모든 happy
 * path / 검증 / 실패 매핑을 한 번에 격리 검증. CR builder 의 map 형식은 {@link VeleroCrBuilderTest}.
 */
class VeleroCrServicesDispatchTest {

	private AgentSessionRegistry registry;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
	}

	private void stubOk() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder()
								.setStatus(Status.OK)
								.setResult(Struct.newBuilder().build())
								.build()));
	}

	// ===== VeleroBackupServiceImpl =====

	@Test
	void backup_dispatchesApplyManifest_returnsSubmitted() {
		stubOk();
		var svc = new VeleroBackupServiceImpl(registry);

		VeleroCrResult result = svc.create("c1", VeleroBackupRequest.fullCluster("backup-1"));

		assertThat(result.clusterName()).isEqualTo("c1");
		assertThat(result.kind()).isEqualTo("Backup");
		assertThat(result.name()).isEqualTo("backup-1");
		assertThat(result.namespace()).isEqualTo("velero");
		assertThat(result.phase()).isEqualTo("Submitted");
	}

	@Test
	void backup_blankName_throwsInvalidParams() {
		var svc = new VeleroBackupServiceImpl(registry);
		var bad = new VeleroBackupRequest("  ", "velero",
				java.util.List.of(), java.util.List.of(), java.util.List.of(),
				null, true, "default", null);

		assertThatThrownBy(() -> svc.create("c1", bad))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("INVALID_PARAMS"));
	}

	@Test
	void backup_noActiveSession_mapsToNoActiveAgent() {
		CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
		failed.completeExceptionally(new NoActiveSessionException("no session"));
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(failed);

		var svc = new VeleroBackupServiceImpl(registry);
		assertThatThrownBy(() -> svc.create("c1", VeleroBackupRequest.fullCluster("b")))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("NO_ACTIVE_AGENT"));
	}

	@Test
	void backup_agentErrorResponse_mapsToVeleroApiError() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder()
								.setStatus(Status.FAILED)
								.setErrorCode("")
								.setErrorMessage("velero ns missing")
								.build()));

		var svc = new VeleroBackupServiceImpl(registry);
		assertThatThrownBy(() -> svc.create("c1", VeleroBackupRequest.fullCluster("b")))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("VELERO_API_ERROR"));
	}

	// ===== VeleroRestoreServiceImpl =====

	@Test
	void restore_dispatches_returnsSubmitted() {
		stubOk();
		var svc = new VeleroRestoreServiceImpl(registry);

		VeleroCrResult result = svc.create("c1",
				VeleroRestoreRequest.fromBackup("restore-1", "backup-1"));

		assertThat(result.kind()).isEqualTo("Restore");
		assertThat(result.name()).isEqualTo("restore-1");
		assertThat(result.phase()).isEqualTo("Submitted");
	}

	@Test
	void restore_blankBackupName_throwsInvalidParams() {
		var svc = new VeleroRestoreServiceImpl(registry);
		var bad = new VeleroRestoreRequest("restore-1", "velero", "",
				java.util.List.of(), java.util.List.of(), java.util.List.of(),
				java.util.Map.of(), true);

		assertThatThrownBy(() -> svc.create("c1", bad))
				.isInstanceOf(BackupException.class)
				.hasMessageContaining("backupName required");
	}

	@Test
	void restore_blankName_throwsInvalidParams() {
		var svc = new VeleroRestoreServiceImpl(registry);
		var bad = new VeleroRestoreRequest(null, "velero", "backup-1",
				java.util.List.of(), java.util.List.of(), java.util.List.of(),
				java.util.Map.of(), true);

		assertThatThrownBy(() -> svc.create("c1", bad))
				.isInstanceOf(BackupException.class)
				.hasMessageContaining("restore name required");
	}

	// ===== VeleroScheduleServiceImpl =====

	@Test
	void schedule_dispatches_returnsSubmitted() {
		stubOk();
		var svc = new VeleroScheduleServiceImpl(registry);

		VeleroCrResult result = svc.create("c1",
				VeleroScheduleRequest.dailyFull("daily-full", "0 2 * * *"));

		assertThat(result.kind()).isEqualTo("Schedule");
	}

	@Test
	void schedule_blankCron_throwsInvalidParams() {
		var svc = new VeleroScheduleServiceImpl(registry);
		var bad = new VeleroScheduleRequest("s", "velero", "  ",
				VeleroBackupRequest.fullCluster("t"), false);

		assertThatThrownBy(() -> svc.create("c1", bad))
				.isInstanceOf(BackupException.class)
				.hasMessageContaining("cron required");
	}

	@Test
	void schedule_nullTemplate_throwsInvalidParams() {
		var svc = new VeleroScheduleServiceImpl(registry);
		var bad = new VeleroScheduleRequest("s", "velero", "0 2 * * *", null, false);

		assertThatThrownBy(() -> svc.create("c1", bad))
				.isInstanceOf(BackupException.class)
				.hasMessageContaining("backup template required");
	}
}
