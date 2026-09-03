package io.aipaas.cluster.agent.backup.velero.impl;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.core.BackupException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Velero CR (Backup/Restore/Schedule) 의 공용 dispatch.
 *
 * <p>Velero controller 가 CR watch 로 작업 trigger — starter 는 CR YAML 생성 + APPLY_MANIFEST 호출 만.
 * 결과는 비동기로 CR 의 status 필드에 반영 — caller 가 별도 GET_RESOURCE 로 polling.
 *
 * <p>YAML 직렬화 = SnakeYAML. JSON 으로도 APPLY_MANIFEST 가능하지만 YAML 이 K8s 표준이라 디버그 용이.
 */
@Slf4j
@RequiredArgsConstructor
class VeleroCrApplier {

	private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(30);

	private final AgentSessionRegistry sessionRegistry;

	/**
	 * CR map → YAML serialize → APPLY_MANIFEST. CR 이 이미 있으면 SSA force=true 로 덮어쓰기.
	 *
	 * @param clusterName 대상 cluster
	 * @param namespace   CR namespace (보통 "velero")
	 * @param cr          K8s CR 자료구조 (apiVersion / kind / metadata / spec / ...)
	 * @return 응답 result Struct (applied[] 등) — caller 가 필요하면 deserialize
	 */
	Struct applyCr(String clusterName, String namespace, Map<String, Object> cr) {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		String manifest = new Yaml(options).dump(cr);

		Struct params = Struct.newBuilder()
				.putFields("manifest", strVal(manifest))
				.putFields("namespace", strVal(namespace))
				.putFields("force", strVal("true"))
				.build();

		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(CommandType.APPLY_MANIFEST)
						.setParams(params)
						.setTimeoutSeconds((int) APPLY_TIMEOUT.getSeconds())
						.build());

		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) APPLY_TIMEOUT.getSeconds())
					.get(APPLY_TIMEOUT.toMillis() + 2_000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				throw new BackupException(
						resp.getErrorCode().isEmpty() ? "VELERO_API_ERROR" : resp.getErrorCode(),
						resp.getErrorMessage());
			}
			return resp.getResult();
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			throw new BackupException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			throw new BackupException("TIMEOUT",
					"timeout applying CR (cluster=" + clusterName + ")", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException) {
				throw new BackupException("NO_ACTIVE_AGENT", "no active agent stream", cause);
			}
			throw new BackupException("AGENT_CALL_FAILED",
					cause == null ? e.toString() : cause.toString(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BackupException("INTERRUPTED", "interrupted", e);
		}
	}

	private static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s == null ? "" : s).build();
	}
}
