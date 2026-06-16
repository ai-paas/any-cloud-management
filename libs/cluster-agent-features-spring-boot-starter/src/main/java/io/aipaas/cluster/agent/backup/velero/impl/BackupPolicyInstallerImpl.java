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
import io.aipaas.cluster.agent.backup.velero.BackupPolicy;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyApplyResult;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyCatalog;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * BackupPolicyInstaller 구현.
 *
 * <p>YAML 의 {@code ${NAMESPACE}} placeholder 를 대상 namespace 로 치환 후 APPLY_MANIFEST.
 * 카탈로그가 manifest 를 YAML 그대로 보유하므로 VeleroCrApplier 의 Map → YAML 단계 생략.
 *
 * <p>패턴 출처: observability-starter 의 AlertRuleInstaller.
 */
@Slf4j
@RequiredArgsConstructor
public class BackupPolicyInstallerImpl implements BackupPolicyInstaller {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
	private static final String DEFAULT_NAMESPACE = "velero";

	private final AgentSessionRegistry sessionRegistry;
	private final BackupPolicyCatalog catalog;

	@Override
	public BackupPolicyApplyResult install(String clusterName, String policyId, String namespace) {
		BackupPolicy policy = catalog.byId(policyId)
				.orElseThrow(() -> new BackupException("INVALID_PARAMS",
						"unknown velero policy id: " + policyId));
		String ns = blankOr(namespace, DEFAULT_NAMESPACE);
		String manifest = substitute(policy.manifestYaml(), ns);

		dispatchApply(clusterName, manifest, ns);
		return new BackupPolicyApplyResult(
				clusterName, policyId, ns,
				"anycloud-" + policyId, "applied");
	}

	@Override
	public List<BackupPolicyApplyResult> installAll(String clusterName, String namespace) {
		List<BackupPolicyApplyResult> out = new ArrayList<>();
		for (BackupPolicy p : catalog.list()) {
			try {
				out.add(install(clusterName, p.id(), namespace));
			} catch (BackupException e) {
				log.warn("velero policy install-all: {} failed on cluster {} — {}",
						p.id(), clusterName, e.getMessage());
				out.add(new BackupPolicyApplyResult(
						clusterName, p.id(), blankOr(namespace, DEFAULT_NAMESPACE),
						"anycloud-" + p.id(), "failed: " + e.errorCode()));
			}
		}
		return out;
	}

	@Override
	public BackupPolicyApplyResult uninstall(String clusterName, String policyId, String namespace) {
		String ns = blankOr(namespace, DEFAULT_NAMESPACE);
		String resourceName = "anycloud-" + policyId;
		dispatchDelete(clusterName, "Schedule", ns, resourceName);
		return new BackupPolicyApplyResult(clusterName, policyId, ns, resourceName, "deleted");
	}

	private void dispatchApply(String clusterName, String manifest, String namespace) {
		Struct params = Struct.newBuilder()
				.putFields("manifest", strVal(manifest))
				.putFields("namespace", strVal(namespace))
				.putFields("force", strVal("true"))
				.build();
		send(clusterName, CommandType.APPLY_MANIFEST, params);
	}

	private void dispatchDelete(String clusterName, String kind, String namespace, String name) {
		Struct params = Struct.newBuilder()
				.putFields("kind", strVal(kind))
				.putFields("namespace", strVal(namespace))
				.putFields("name", strVal(name))
				.build();
		send(clusterName, CommandType.DELETE_RESOURCE, params);
	}

	private void send(String clusterName, CommandType type, Struct params) {
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(type)
						.setParams(params)
						.setTimeoutSeconds((int) DEFAULT_TIMEOUT.getSeconds())
						.build());
		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) DEFAULT_TIMEOUT.getSeconds())
					.get(DEFAULT_TIMEOUT.toMillis() + 2_000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				throw new BackupException(
						resp.getErrorCode().isEmpty() ? "VELERO_API_ERROR" : resp.getErrorCode(),
						resp.getErrorMessage());
			}
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			throw new BackupException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			throw new BackupException("TIMEOUT",
					"timeout applying policy (cluster=" + clusterName + ")", e);
		} catch (ExecutionException e) {
			// CompletableFuture.get() 의 ExecutionException unwrap. async path 에서
			// NoActiveSessionException 이 wrap 된 채 도착 → 직접 catch 안 됨. cause 검사로
			// NO_ACTIVE_AGENT 정확 매핑.
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException) {
				throw new BackupException("NO_ACTIVE_AGENT",
						"no active agent stream for cluster " + clusterName, cause);
			}
			throw new BackupException("AGENT_CALL_FAILED",
					cause == null ? e.toString() : cause.toString(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BackupException("INTERRUPTED", "interrupted", e);
		}
	}

	static String substitute(String yaml, String namespace) {
		return yaml.replace("${NAMESPACE}", namespace == null ? "" : namespace);
	}

	private static String blankOr(String s, String fallback) {
		return (s == null || s.isBlank()) ? fallback : s;
	}

	private static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s == null ? "" : s).build();
	}
}
