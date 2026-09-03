package io.aipaas.cluster.agent.backup.node.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.aipaas.cluster.agent.backup.node.BackupResult;
import io.aipaas.cluster.agent.backup.node.EtcdBackupService.EtcdBackupOptions;
import io.aipaas.cluster.agent.backup.node.PkiBackupService.PkiBackupOptions;
import io.aipaas.cluster.agent.backup.core.BackupException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * Etcd / PKI backup service 의 dispatch + 응답 매핑 + 실패 매핑 회귀.
 *
 * <p>두 service 가 공용 {@link BackupDispatchSupport} 를 사용 — payload (binary_payload) 가 비면 실패로
 * 매핑, fields (size/sha256/metadata/node_name) 가 BackupResult 로 정확히 매핑되는지 한 번에 검증.
 */
class EtcdAndPkiBackupServiceImplTest {

	private AgentSessionRegistry registry;

	@BeforeEach
	void setUp() {
		registry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
	}

	private static CommandResponse okBackup(byte[] payload, long size, String sha, String meta, String node) {
		Struct.Builder fields = Struct.newBuilder()
				.putFields("size_bytes", Value.newBuilder().setNumberValue(size).build())
				.putFields("sha256", Value.newBuilder().setStringValue(sha).build())
				.putFields("metadata", Value.newBuilder().setStringValue(meta).build())
				.putFields("node_name", Value.newBuilder().setStringValue(node).build());
		return CommandResponse.newBuilder()
				.setStatus(Status.OK)
				.setBinaryPayload(ByteString.copyFrom(payload))
				.setResult(fields.build())
				.build();
	}

	// ===== etcd =====

	@Test
	void etcdBackup_dispatchesBackupEtcd_returnsPayload() {
		byte[] snapshot = new byte[]{1, 2, 3, 4, 5};
		when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						okBackup(snapshot, snapshot.length, "deadbeef",
								"etcd_version=3.5.10", "master-1")));

		var svc = new EtcdBackupServiceImpl(registry, Duration.ofSeconds(1));
		BackupResult result = svc.backup("c1", EtcdBackupOptions.defaults());

		assertThat(result.clusterName()).isEqualTo("c1");
		assertThat(result.nodeName()).isEqualTo("master-1");
		assertThat(result.payload()).containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
		assertThat(result.sizeBytes()).isEqualTo(5);
		assertThat(result.sha256Hex()).isEqualTo("deadbeef");
		assertThat(result.metadata()).contains("3.5.10");
	}

	@Test
	void etcdBackup_passesEndpointAndCertParams() {
		byte[] snapshot = new byte[]{0};
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						okBackup(snapshot, 1, "sha", "m", "n")));
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		var svc = new EtcdBackupServiceImpl(registry, Duration.ofSeconds(1));
		svc.backup("c1", new EtcdBackupOptions(
				"https://etcd:2379", "/ca.crt", "/cli.crt", "/cli.key", 4 * 1024 * 1024));

		Mockito.verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		ControlMessage msg = captor.getValue().build();
		assertThat(msg.getCommand().getType()).isEqualTo(CommandType.BACKUP_ETCD);
		Struct params = msg.getCommand().getParams();
		assertThat(params.getFieldsOrThrow("endpoint").getStringValue()).isEqualTo("https://etcd:2379");
		assertThat(params.getFieldsOrThrow("ca_cert_path").getStringValue()).isEqualTo("/ca.crt");
		assertThat(params.getFieldsOrThrow("chunk_size").getStringValue())
				.isEqualTo(String.valueOf(4 * 1024 * 1024));
	}

	@Test
	void etcdBackup_emptyPayload_throwsBackupFailed() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						okBackup(new byte[0], 0, "", "", "n")));

		var svc = new EtcdBackupServiceImpl(registry, Duration.ofSeconds(1));
		assertThatThrownBy(() -> svc.backup("c1", EtcdBackupOptions.defaults()))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("BACKUP_FAILED"))
				.hasMessageContaining("binary_payload is empty");
	}

	@Test
	void etcdBackup_agentFailureKeepsErrorCode() {
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.FAILED)
								.setErrorCode("ETCDCTL_NOT_FOUND")
								.setErrorMessage("etcdctl binary missing").build()));

		var svc = new EtcdBackupServiceImpl(registry, Duration.ofSeconds(1));
		assertThatThrownBy(() -> svc.backup("c1", EtcdBackupOptions.defaults()))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("ETCDCTL_NOT_FOUND"));
	}

	@Test
	void etcdBackup_noActiveSession_mapsToNoActiveAgent() {
		CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
		failed.completeExceptionally(new NoActiveSessionException("no session"));
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(failed);

		var svc = new EtcdBackupServiceImpl(registry, Duration.ofSeconds(1));
		assertThatThrownBy(() -> svc.backup("c1", EtcdBackupOptions.defaults()))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("NO_ACTIVE_AGENT"));
	}

	// ===== PKI =====

	@Test
	void pkiBackup_dispatchesBackupPki_serializesIncludePathsAsJson() {
		byte[] tar = new byte[]{1, 2};
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						okBackup(tar, 2, "deadbeef", "file_count=4", "master-1")));
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		var svc = new PkiBackupServiceImpl(registry, Duration.ofSeconds(1));
		BackupResult result = svc.backup("c1", PkiBackupOptions.onlyCaAndSa());

		assertThat(result.payload()).containsExactly((byte) 1, (byte) 2);
		Mockito.verify(registry).sendCommand(eq("c1"), captor.capture(), anyInt());
		Struct params = captor.getValue().build().getCommand().getParams();
		// include_paths 가 JSON array 문자열로 직렬화되어 있어야 함.
		String includePaths = params.getFieldsOrThrow("include_paths").getStringValue();
		assertThat(includePaths)
				.contains("ca.crt")
				.contains("ca.key")
				.contains("sa.key")
				.contains("sa.pub");
		assertThat(captor.getValue().build().getCommand().getType()).isEqualTo(CommandType.BACKUP_PKI);
	}

	@Test
	void pkiBackup_emptyIncludePaths_serializesAsBlank() {
		byte[] tar = new byte[]{1};
		when(registry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						okBackup(tar, 1, "", "", "")));
		ArgumentCaptor<ControlMessage.Builder> captor = ArgumentCaptor.forClass(ControlMessage.Builder.class);

		var svc = new PkiBackupServiceImpl(registry, Duration.ofSeconds(1));
		svc.backup("c1", new PkiBackupOptions(List.of(), 0));

		Mockito.verify(registry).sendCommand(any(), captor.capture(), anyInt());
		Struct params = captor.getValue().build().getCommand().getParams();
		// 빈 list 면 "" 전송 — agent 가 전체 백업 트리거.
		assertThat(params.getFieldsOrThrow("include_paths").getStringValue()).isEmpty();
	}
}
