package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.SessionClosedException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Day-2 K8s ops 를 agent gRPC stream 으로 routing 하는 starter-side facade.
 * <ul>
 *   <li>지원: getPodLogs / getResource / listResourcesPaginated / deleteResource / applyResource</li>
 *   <li>flag {@code cluster-agent.routing.enabled} — false 면 항상 {@link #isActiveFor} = false</li>
 *   <li>session 없으면 isActiveFor=false → caller 가 fabric8 등 fallback 결정</li>
 * </ul>
 * @see docs/architecture/k8s-access-paths.md
 */
@Slf4j
@RequiredArgsConstructor
public class KubeResourceService {

	/** APPLY_MANIFEST 만 일반 명령보다 timeout 큼 — 60s + 10s cushion. */
	private static final long APPLY_TIMEOUT_SECONDS = 60L + 10L;

	private final AgentSessionRegistry sessionRegistry;
	private final AgentCommandRouter commandRouter;
	private final ObjectMapper objectMapper;
	private final boolean enabled;
	private final int commandTimeoutSeconds;

	/** routing flag ON + active session 있으면 true. false 면 caller fallback. */
	public boolean isActiveFor(String clusterName) {
		if (!enabled) {
			return false;
		}
		return sessionRegistry.find(clusterName).isPresent();
	}

	/** GET_LOG — kubectl logs 등가. */
	public String getPodLogs(String clusterName, String namespace, String podName, String container,
			Integer tailLines, boolean previous, Integer sinceSeconds) {
		var future = commandRouter.getLog(
				clusterName,
				namespace == null ? "default" : namespace,
				podName,
				container == null ? "" : container,
				tailLines == null ? 100 : tailLines,
				previous,
				sinceSeconds == null ? 0 : sinceSeconds);

		CommandResponse resp = await(future, "GET_LOG", commandTimeoutSeconds + 5L);
		requireOk(resp, "GET_LOG");

		Value logField = resp.getResult().getFieldsOrDefault("log", null);
		if (logField == null) {
			throw new KubeRoutingException("Agent GET_LOG response missing 'log' field");
		}
		return logField.getStringValue();
	}

	/** GET_RESOURCE — kubectl get -o json 등가. */
	public JsonNode getResource(String clusterName, String namespace, String kind, String name) {
		var future = commandRouter.getResource(clusterName,
				namespace == null ? "" : namespace, kind, name);

		CommandResponse resp = await(future, "GET_RESOURCE", commandTimeoutSeconds + 5L);
		requireOk(resp, "GET_RESOURCE");

		Value resourceField = resp.getResult().getFieldsOrDefault("resource", null);
		if (resourceField == null) {
			throw new KubeRoutingException("Agent GET_RESOURCE response missing 'resource' field");
		}
		try {
			return objectMapper.readTree(resourceField.getStringValue());
		} catch (JsonProcessingException e) {
			throw new KubeRoutingException("Failed to parse GET_RESOURCE JSON: " + e.getMessage(), e);
		}
	}

	/** LIST_RESOURCES (paginated) — kubectl get &lt;kind&gt; 등가. */
	public KubeResourcePage listResourcesPaginated(String clusterName, String namespace, String kind,
			int limit, String continueToken, String labelSelector) {
		var future = commandRouter.listResources(clusterName,
				namespace == null ? "" : namespace, kind, limit, continueToken, labelSelector);

		CommandResponse resp = await(future, "LIST_RESOURCES", commandTimeoutSeconds + 5L);
		requireOk(resp, "LIST_RESOURCES");

		Value itemsField = resp.getResult().getFieldsOrDefault("items", null);
		if (itemsField == null) {
			throw new KubeRoutingException("Agent LIST_RESOURCES response missing 'items' field");
		}
		JsonNode itemsJson;
		try {
			itemsJson = objectMapper.readTree(itemsField.getStringValue());
		} catch (JsonProcessingException e) {
			throw new KubeRoutingException("Failed to parse LIST_RESOURCES items JSON: " + e.getMessage(), e);
		}
		String nextToken = resp.getResult().getFieldsOrDefault("continue_token",
				Value.newBuilder().setStringValue("").build()).getStringValue();
		int count = (int) resp.getResult().getFieldsOrDefault("returned_count",
				Value.newBuilder().setNumberValue(0).build()).getNumberValue();
		return new KubeResourcePage(
				clusterName,
				namespace == null || namespace.isBlank() ? null : namespace,
				kind,
				itemsJson,
				nextToken == null || nextToken.isBlank() ? null : nextToken,
				count);
	}

	/** DELETE_RESOURCE — kubectl delete 등가. AllowList 검증은 agent. */
	public boolean deleteResource(String clusterName, String namespace, String kind, String name) {
		var future = commandRouter.deleteResource(clusterName,
				namespace == null ? "" : namespace, kind, name);

		CommandResponse resp = await(future, "DELETE_RESOURCE", commandTimeoutSeconds + 5L);
		requireOk(resp, "DELETE_RESOURCE");

		Value deleted = resp.getResult().getFieldsOrDefault("deleted", null);
		return deleted != null && deleted.getBoolValue();
	}

	/** APPLY_MANIFEST — kubectl apply 등가. multi-doc YAML/JSON. 단일 자원이면 single, 멀티이면 array. */
	public JsonNode applyResource(String clusterName, String namespace, String manifest) {
		return applyResource(clusterName, namespace, manifest, false);
	}

	/**
	 * APPLY_MANIFEST 의 force 변형.
	 *
	 * <p>{@code force=true} → server-side apply force conflicts. 기존에 {@code kubectl edit} 등
	 * 다른 fieldManager 가 소유한 필드를 강제로 ownership 가져옴. agent install / chart upgrade
	 * 같은 운영 도구 입장에선 필수 (사용자가 ConfigMap 직접 편집한 경우 회복).
	 */
	public JsonNode applyResource(String clusterName, String namespace, String manifest, boolean force) {
		return applyResource(clusterName, namespace, manifest, force, false);
	}

	/**
	 * APPLY_MANIFEST 의 dry-run 변형.
	 *
	 * <p>{@code dryRun=true} → K8s API server 가 admission/validation 만 수행하고 etcd 에 persist
	 * 하지 않음. frontend 의 "검증" / "미리보기" 버튼에 사용. 응답의 applied[] 는 정상이지만 실제
	 * cluster state 는 변경 없음.
	 */
	public JsonNode applyResource(String clusterName, String namespace, String manifest, boolean force, boolean dryRun) {
		var future = commandRouter.applyManifest(clusterName,
				namespace == null ? "" : namespace, manifest, force, dryRun);

		CommandResponse resp = await(future, "APPLY_MANIFEST", APPLY_TIMEOUT_SECONDS);
		requireOk(resp, "APPLY_MANIFEST");

		Value appliedField = resp.getResult().getFieldsOrDefault("applied", null);
		if (appliedField == null || !appliedField.hasListValue()) {
			throw new KubeRoutingException("Agent APPLY_MANIFEST response missing 'applied' list");
		}
		var listValues = appliedField.getListValue().getValuesList();
		try {
			ArrayNode arr = objectMapper.createArrayNode();
			for (Value v : listValues) {
				if (v.hasStructValue()) {
					String json = JsonFormat.printer()
							.omittingInsignificantWhitespace()
							.print(v.getStructValue());
					arr.add(objectMapper.readTree(json));
				}
			}
			return arr.size() == 1 ? arr.get(0) : arr;
		} catch (Exception e) {
			throw new KubeRoutingException("Failed to parse APPLY_MANIFEST response: " + e.getMessage(), e);
		}
	}

	/**
	 * LIST_RESOURCE_KINDS — cluster 의 discovery API 가 노출하는 모든 API resource (kind) enumerate.
	 * UI 의 "kind picker" / "resource browser" 채울 때 사용. CRD 도 자동 포함.
	 *
	 * <p>응답: {@code List<ResourceKindInfo>} — group/plural 순으로 정렬됨 (agent 측 정렬 보장).
	 */
	public List<ResourceKindInfo> listResourceKinds(String clusterName) {
		var future = commandRouter.listResourceKinds(clusterName);
		CommandResponse resp = await(future, "LIST_RESOURCE_KINDS", commandTimeoutSeconds + 5L);
		requireOk(resp, "LIST_RESOURCE_KINDS");

		Value kindsField = resp.getResult().getFieldsOrDefault("kinds", null);
		if (kindsField == null || !kindsField.hasListValue()) {
			throw new KubeRoutingException("Agent LIST_RESOURCE_KINDS response missing 'kinds' list");
		}
		List<ResourceKindInfo> out = new ArrayList<>(kindsField.getListValue().getValuesCount());
		for (Value v : kindsField.getListValue().getValuesList()) {
			if (!v.hasStructValue()) {
				continue;
			}
			var fields = v.getStructValue().getFieldsMap();
			List<String> shortNames = Collections.emptyList();
			Value sn = fields.get("short_names");
			if (sn != null && sn.hasListValue()) {
				shortNames = new ArrayList<>(sn.getListValue().getValuesCount());
				for (Value s : sn.getListValue().getValuesList()) {
					if (s.hasStringValue() && !s.getStringValue().isBlank()) {
						shortNames.add(s.getStringValue());
					}
				}
			}
			out.add(new ResourceKindInfo(
					stringOrEmpty(fields.get("plural")),
					stringOrEmpty(fields.get("singular")),
					stringOrEmpty(fields.get("kind")),
					stringOrEmpty(fields.get("group")),
					stringOrEmpty(fields.get("version")),
					boolOrFalse(fields.get("namespaced")),
					shortNames));
		}
		return out;
	}

	/**
	 * RESOLVE_RESOURCE — 입력 (단축이름/plural/PascalCase) → 정규화. 실패 시
	 * {@link UnsupportedKindException} (suggestions 포함). 다른 모든 실패는 KubeRoutingException.
	 */
	public ResolvedResource resolveResource(String clusterName, String input) {
		var future = commandRouter.resolveResource(clusterName, input);
		CommandResponse resp = await(future, "RESOLVE_RESOURCE", commandTimeoutSeconds + 5L);

		if (resp.getStatus() != Status.OK) {
			if ("UNSUPPORTED_KIND".equals(resp.getErrorCode())) {
				List<String> suggestions = Collections.emptyList();
				Value sn = resp.getResult().getFieldsOrDefault("suggestions", null);
				if (sn != null && sn.hasListValue()) {
					suggestions = new ArrayList<>(sn.getListValue().getValuesCount());
					for (Value v : sn.getListValue().getValuesList()) {
						if (v.hasStringValue() && !v.getStringValue().isBlank()) {
							suggestions.add(v.getStringValue());
						}
					}
				}
				throw new UnsupportedKindException(input, resp.getErrorMessage(), suggestions);
			}
			throw new KubeRoutingException(
					"Agent RESOLVE_RESOURCE returned " + resp.getStatus() + " (" + resp.getErrorCode() + "): "
							+ resp.getErrorMessage());
		}
		var fields = resp.getResult().getFieldsMap();
		List<String> shortNames = Collections.emptyList();
		Value sn = fields.get("short_names");
		if (sn != null && sn.hasListValue()) {
			shortNames = new ArrayList<>(sn.getListValue().getValuesCount());
			for (Value v : sn.getListValue().getValuesList()) {
				if (v.hasStringValue() && !v.getStringValue().isBlank()) {
					shortNames.add(v.getStringValue());
				}
			}
		}
		return new ResolvedResource(
				stringOrEmpty(fields.get("plural")),
				stringOrEmpty(fields.get("singular")),
				stringOrEmpty(fields.get("kind")),
				stringOrEmpty(fields.get("group")),
				stringOrEmpty(fields.get("version")),
				boolOrFalse(fields.get("namespaced")),
				shortNames);
	}

	/**
	 * GET_AGENT_CONFIG — agent 의 현재 in-memory allowlist + resource_policy snapshot.
	 * ConfigMap edit 후 reload 검증 / UI 운영자 페이지 데이터 소스.
	 */
	public AgentPolicySnapshot getAgentConfig(String clusterName) {
		var future = commandRouter.getAgentConfig(clusterName);
		CommandResponse resp = await(future, "GET_AGENT_CONFIG", commandTimeoutSeconds + 5L);
		requireOk(resp, "GET_AGENT_CONFIG");

		var fields = resp.getResult().getFieldsMap();

		List<String> allowedNs = stringList(fields.get("allowed_namespaces"));
		boolean allowAllNs = boolOrFalse(fields.get("allow_all_namespaces"));
		List<String> allowedCmds = stringList(fields.get("allowed_commands"));
		List<String> allowedCharts = stringList(fields.get("allowed_charts"));
		List<String> allowedExecNs = stringList(fields.get("allowed_exec_namespaces"));
		boolean allowAllExecNs = boolOrFalse(fields.get("allow_all_exec_namespaces"));

		AgentPolicySnapshot.ResourcePolicy policy = parseResourcePolicy(fields.get("resource_policy"));

		Instant lastReloadAt = parseInstantOrNull(stringOrEmpty(fields.get("last_reload_at")));
		String resourceVersion = stringOrEmpty(fields.get("configmap_resource_version"));

		return new AgentPolicySnapshot(
				allowedNs, allowAllNs, allowedCmds, allowedCharts,
				allowedExecNs, allowAllExecNs, policy,
				lastReloadAt,
				resourceVersion.isBlank() ? null : resourceVersion);
	}

	private static AgentPolicySnapshot.ResourcePolicy parseResourcePolicy(Value v) {
		if (v == null || !v.hasStructValue()) {
			return null;       // legacy nil policy
		}
		Map<String, Value> rp = v.getStructValue().getFieldsMap();
		String mode = stringOrEmpty(rp.get("mode"));
		if (mode.isBlank()) {
			return null;       // ConfigMap 에 resource_policy 없음 → nil
		}
		List<AgentPolicySnapshot.ResourceRule> deny = parseRuleList(rp.get("deny"));
		List<AgentPolicySnapshot.ResourceRule> allow = parseRuleList(rp.get("allow"));
		return new AgentPolicySnapshot.ResourcePolicy(mode, deny, allow);
	}

	private static List<AgentPolicySnapshot.ResourceRule> parseRuleList(Value v) {
		if (v == null || !v.hasListValue()) {
			return Collections.emptyList();
		}
		List<AgentPolicySnapshot.ResourceRule> out = new ArrayList<>();
		for (Value item : v.getListValue().getValuesList()) {
			if (!item.hasStructValue()) {
				continue;
			}
			Map<String, Value> rule = item.getStructValue().getFieldsMap();
			out.add(new AgentPolicySnapshot.ResourceRule(
					stringOrEmpty(rule.get("kind")),
					stringOrEmpty(rule.get("namespace"))));
		}
		return out;
	}

	private static List<String> stringList(Value v) {
		if (v == null || !v.hasListValue()) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>(v.getListValue().getValuesCount());
		for (Value item : v.getListValue().getValuesList()) {
			if (item.hasStringValue() && !item.getStringValue().isBlank()) {
				out.add(item.getStringValue());
			}
		}
		return out;
	}

	private static Instant parseInstantOrNull(String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(iso);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * ENSURE_AGENT_CONFIG_ANNOTATIONS — agent 의 ConfigMap 에 {@code helm.sh/resource-policy: keep}
	 * annotation 만 추가. 멱등 — 이미 있으면 no-op. backend startup hook 에서 모든 ACTIVE cluster 의
	 * legacy ConfigMap 을 backfill.
	 *
	 * @return 결과 record {@code (alreadyPresent, resourceVersion)}.
	 */
	public EnsureAnnotationResult ensureAgentConfigAnnotations(String clusterName) {
		var future = commandRouter.ensureAgentConfigAnnotations(clusterName);
		CommandResponse resp = await(future, "ENSURE_AGENT_CONFIG_ANNOTATIONS", commandTimeoutSeconds + 5L);
		requireOk(resp, "ENSURE_AGENT_CONFIG_ANNOTATIONS");

		var fields = resp.getResult().getFieldsMap();
		boolean alreadyPresent = boolOrFalse(fields.get("already_present"));
		String rv = stringOrEmpty(fields.get("resource_version"));
		return new EnsureAnnotationResult(alreadyPresent, rv);
	}

	public record EnsureAnnotationResult(boolean alreadyPresent, String resourceVersion) {
	}

	/**
	 * APPLY_AGENT_CONFIG — agent 의 ConfigMap 을 새 snapshot 으로 갱신. backend 에는 DB 저장 안 함
	 * (ConfigMap = source of truth). 변경 이력은 audit_log 가 담당.
	 *
	 * <p>caller 책임: 4개 list 와 resource_policy YAML 을 미리 직렬화. validator 통과 후 호출 권장.
	 *
	 * @return 적용된 ConfigMap 의 새 {@code resourceVersion}. caller (audit) 가 추적용으로 사용 가능.
	 */
	public String applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml) {
		return applyAgentConfig(clusterName, allowedNamespacesJson, allowedCommandsJson,
				allowedChartsJson, allowedExecNamespacesJson, resourcePolicyYaml, null);
	}

	/**
	 * Hybrid helm-repo sync overload. {@code helmRepositoriesJson} 가 backend 의 helm_repo 테이블
	 * 직렬화 결과 (JSON array of objects). null 또는 빈 문자열은 "변경 없음" 으로 해석되지 않음 — agent 가 빈
	 * array 로 받아 모든 등록 repo 를 unregister. 호출자가 의도 명확히 보내야.
	 */
	public String applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml,
			String helmRepositoriesJson) {
		return applyAgentConfig(clusterName, allowedNamespacesJson, allowedCommandsJson, allowedChartsJson,
				allowedExecNamespacesJson, resourcePolicyYaml, helmRepositoriesJson, null);
	}

	/**
	 * Fleet-wide OidcGroupBinding sync overload. {@code oidcBindingsJson} 가 fleet-wide active
	 * binding 의 JSON array (backend canonical). null 이면 빈 array — agent 가 모든 binding 제거.
	 */
	public String applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml,
			String helmRepositoriesJson, String oidcBindingsJson) {
		var future = commandRouter.applyAgentConfig(clusterName,
				allowedNamespacesJson, allowedCommandsJson, allowedChartsJson,
				allowedExecNamespacesJson, resourcePolicyYaml, helmRepositoriesJson, oidcBindingsJson);
		CommandResponse resp = await(future, "APPLY_AGENT_CONFIG", commandTimeoutSeconds + 5L);
		requireOk(resp, "APPLY_AGENT_CONFIG");

		Value rvField = resp.getResult().getFieldsOrDefault("resource_version", null);
		return rvField != null && rvField.hasStringValue() ? rvField.getStringValue() : "";
	}

	private static String stringOrEmpty(Value v) {
		if (v == null) {
			return "";
		}
		return v.hasStringValue() ? v.getStringValue() : "";
	}

	private static boolean boolOrFalse(Value v) {
		return v != null && v.hasBoolValue() && v.getBoolValue();
	}

	// ----- internal -----

	/** future 대기 + 모든 실패 유형을 {@link KubeRoutingException} 으로 통일. */
	private static CommandResponse await(java.util.concurrent.CompletableFuture<CommandResponse> future,
			String opName, long timeoutSeconds) {
		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new KubeRoutingException("Agent " + opName + " timeout after " + timeoutSeconds + "s", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new KubeRoutingException("Interrupted waiting for agent " + opName, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			if (cause instanceof NoActiveSessionException || cause instanceof SessionClosedException) {
				throw new KubeRoutingException("No active agent session: " + cause.getMessage(), cause);
			}
			throw new KubeRoutingException("Agent " + opName + " failed: " + cause.getMessage(), cause);
		}
	}

	private static void requireOk(CommandResponse resp, String opName) {
		if (resp.getStatus() != Status.OK) {
			throw new KubeRoutingException(
					"Agent " + opName + " returned " + resp.getStatus() + " (" + resp.getErrorCode() + "): "
							+ resp.getErrorMessage());
		}
	}
}
