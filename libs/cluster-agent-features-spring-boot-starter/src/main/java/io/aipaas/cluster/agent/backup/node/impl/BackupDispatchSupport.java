package io.aipaas.cluster.agent.backup.node.impl;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.node.BackupResult;
import io.aipaas.cluster.agent.backup.core.BackupException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * etcd / pki backup 의 공통 dispatch 로직.
 *
 * <p>두 service impl 이 동일한 RPC dispatch + error mapping 코드를 갖지 않도록 분리. CommandType 과
 * params 만 다르고 result 구조 (binary_payload + size_bytes + sha256 + metadata + node_name) 는 동일.
 */
@Slf4j
@RequiredArgsConstructor
class BackupDispatchSupport {

	private final AgentSessionRegistry sessionRegistry;
	private final Duration timeout;

	BackupResult dispatch(String clusterName, CommandType type, Struct params) {
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(type)
						.setParams(params)
						.setTimeoutSeconds((int) timeout.getSeconds())
						.build());
		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) timeout.getSeconds())
					.get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				throw new BackupException(
						resp.getErrorCode().isEmpty() ? "BACKUP_FAILED" : resp.getErrorCode(),
						resp.getErrorMessage());
			}
			byte[] payload = resp.getBinaryPayload().toByteArray();
			if (payload.length == 0) {
				throw new BackupException("BACKUP_FAILED", "agent returned OK but binary_payload is empty");
			}
			Map<String, Value> fields = resp.getResult().getFieldsMap();
			long size = (long) fields.getOrDefault("size_bytes",
					Value.newBuilder().setNumberValue(payload.length).build()).getNumberValue();
			String sha = readString(fields, "sha256");
			String metadata = readString(fields, "metadata");
			String node = readString(fields, "node_name");
			return new BackupResult(clusterName, node, payload, size, sha, metadata);
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			throw new BackupException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			throw new BackupException("TIMEOUT",
					"timeout waiting for backup response (cluster=" + clusterName + ")", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException) {
				throw new BackupException("NO_ACTIVE_AGENT", "no active agent stream", cause);
			}
			if (cause instanceof AgentSessionRegistry.SessionClosedException) {
				throw new BackupException("NO_ACTIVE_AGENT", "agent stream closed mid-backup", cause);
			}
			throw new BackupException("AGENT_CALL_FAILED",
					cause == null ? e.toString() : cause.toString(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BackupException("INTERRUPTED", "interrupted", e);
		}
	}

	static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s == null ? "" : s).build();
	}

	private static String readString(Map<String, Value> fields, String key) {
		Value v = fields.get(key);
		return v == null ? null : v.getStringValue();
	}
}
