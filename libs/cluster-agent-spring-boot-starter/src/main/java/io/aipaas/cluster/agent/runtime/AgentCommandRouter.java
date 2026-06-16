package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.ImpersonateExtra;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.identity.ImpersonationContext;
import io.aipaas.cluster.agent.identity.ImpersonationIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 외부 (REST controller / 다른 service) 에서 cluster agent 에 명령을 보낼 때 사용하는 facade.
 *
 * <p>실제 명령 직렬화 / pending future 관리는 {@link AgentSessionRegistry} 가 처리. 본 클래스는
 * {@link CommandType} 마다의 helper (param 구성 / 응답 검증) 를 제공.
 */
@Slf4j
public class AgentCommandRouter {

	private static final int DEFAULT_TIMEOUT_SECONDS = 30;
	/** APPLY_MANIFEST 는 server-side apply 가 webhook/admission 등으로 추가 시간 — 60s default. */
	private static final int APPLY_TIMEOUT_SECONDS = 60;
	/** Helm install/uninstall 은 chart 의 wait/hook 으로 분 단위 가능 — 5분 default. */
	private static final int HELM_OPERATION_TIMEOUT_SECONDS = 300;

	private final AgentSessionRegistry sessionRegistry;
	/**
	 * 모든 send() 가 ImpersonationContext.current() 를 읽어 CommandRequest 의 impersonate_* field 를
	 * 자동 주입. ObjectProvider 로 optional 처리 — 구식 backend (interceptor 미등록) 환경에서도 NPE 없이
	 * 동작. holder 가 empty 면 빈 field 로 송신 → admin-equivalent 동작.
	 *
	 * <p>적용 범위: 본 router 를 통과하는 모든 CommandType. 즉 LIST_RESOURCES / GET_RESOURCE /
	 * APPLY_MANIFEST / DELETE_RESOURCE / GET_LOG / RESOLVE_RESOURCE / INSTALL_ADDON 까지 동일.
	 * helm 명령에 impersonation 이 영향 없음 — agent 의 helm path 가 K8s rest.Config 를 사용 안함
	 * (별도 SDK 객체). agent (Go) 단의 dispatcher 가 K8s 호출 path 에서만 impersonate header 적용.
	 *
	 * <p>system action (RabbitMQ listener 의 INSTALL_ADDON, scheduled monitor) 은 caller thread 에
	 * holder 가 set 되지 않으므로 자연스럽게 admin-equivalent — 별도 분기 불필요.
	 */
	private final ObjectProvider<ImpersonationContext> impersonationContextProvider;

	public AgentCommandRouter(AgentSessionRegistry sessionRegistry,
			ObjectProvider<ImpersonationContext> impersonationContextProvider) {
		this.sessionRegistry = sessionRegistry;
		this.impersonationContextProvider = impersonationContextProvider;
	}

	/** Test convenience — impersonation 비활성. */
	public AgentCommandRouter(AgentSessionRegistry sessionRegistry) {
		this.sessionRegistry = sessionRegistry;
		this.impersonationContextProvider = null;
	}

	/** LIST_PODS 명령. namespace 가 null/blank 이면 all-namespaces. */
	public CompletableFuture<CommandResponse> listPods(String clusterName, String namespace) {
		Struct params = struct(Map.of("namespace", namespace == null ? "" : namespace));
		return send(clusterName, CommandType.LIST_PODS, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** GET_LOG. tailLines 0 이면 default, previous=true 면 crash 직전 container 의 이전 로그. */
	public CompletableFuture<CommandResponse> getLog(String clusterName, String namespace, String podName,
			String container, int tailLines, boolean previous, int sinceSeconds) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"pod", podName,
				"container", container == null ? "" : container,
				"tailLines", String.valueOf(tailLines),
				"previous", String.valueOf(previous),
				"sinceSeconds", String.valueOf(sinceSeconds)));
		return send(clusterName, CommandType.GET_LOG, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** DELETE_RESOURCE — kubectl delete 등가. cluster-scoped kind 면 namespace 무시. */
	public CompletableFuture<CommandResponse> deleteResource(String clusterName, String namespace, String kind,
			String name) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"kind", kind == null ? "" : kind,
				"name", name == null ? "" : name));
		return send(clusterName, CommandType.DELETE_RESOURCE, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** GET_RESOURCE — kubectl get -o json 등가. 응답의 "resource" 필드에 JSON-encoded object. */
	public CompletableFuture<CommandResponse> getResource(String clusterName, String namespace, String kind,
			String name) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"kind", kind == null ? "" : kind,
				"name", name == null ? "" : name));
		return send(clusterName, CommandType.GET_RESOURCE, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** LIST_RESOURCES — paginated K8s 자원 list. namespace 가 빈 문자열이면 all-namespaces. */
	public CompletableFuture<CommandResponse> listResources(String clusterName, String namespace, String kind,
			int limit, String continueToken, String labelSelector) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"kind", kind == null ? "" : kind,
				"limit", String.valueOf(limit),
				"continueToken", continueToken == null ? "" : continueToken,
				"labelSelector", labelSelector == null ? "" : labelSelector));
		return send(clusterName, CommandType.LIST_RESOURCES, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** APPLY_MANIFEST — kubectl apply 등가. multi-doc YAML 또는 JSON manifest 적용. */
	public CompletableFuture<CommandResponse> applyManifest(String clusterName, String namespace,
			String manifest, boolean force) {
		return applyManifest(clusterName, namespace, manifest, force, false);
	}

	/**
	 * APPLY_MANIFEST with dry-run.
	 *
	 * <p>{@code dryRun=true} → K8s API server 가 admission / validation 만 수행하고 etcd 에 persist
	 * 하지 않음. frontend 의 저장 전 검증 (validate / preview) 에 사용. 응답의 applied[] 는 정상
	 * 반환되지만 실제 변경은 없으며 result.fields.dry_run 으로 표시.
	 */
	public CompletableFuture<CommandResponse> applyManifest(String clusterName, String namespace,
			String manifest, boolean force, boolean dryRun) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"manifest", manifest == null ? "" : manifest,
				"force", String.valueOf(force),
				"dry_run", String.valueOf(dryRun)));
		return send(clusterName, CommandType.APPLY_MANIFEST, params, APPLY_TIMEOUT_SECONDS);
	}

	/**
	 * INSTALL_ADDON — Helm install (agent 가 in-cluster Helm SDK 호출). AllowList 의
	 * {@code allowed_charts} (repo/chart + version range) 와 {@code allowed_namespaces} 통과 필수.
	 *
	 * @param chart       "repo/name" 형식. agent dispatcher 가 split.
	 * @param repoUrl     helm chart repository URL (선택). 명시 시 agent 가 alias lookup 우회 후 직접
	 *                    chart 다운로드. backend 의 helm_repo table 에서 lookup 한 값. null/blank 이면
	 *                    agent 의 helm repositories.yaml alias resolve fallback (chart-museum 등
	 *                    backend-registered repo 는 agent 에 helm-add 되어있지 않으므로 보통 실패).
	 * @param valuesJson  values 의 JSON 문자열 (선택, null/blank 이면 chart default).
	 */
	public CompletableFuture<CommandResponse> installAddon(String clusterName, String releaseName,
			String chart, String repoUrl, String chartTarballBase64, String version,
			String namespace, String valuesJson, boolean createNamespace) {
		java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
		p.put("release", releaseName == null ? "" : releaseName);
		p.put("chart", chart == null ? "" : chart);
		p.put("repoUrl", repoUrl == null ? "" : repoUrl);
		// Backend 가 pre-fetch 한 chart .tgz 를 base64 로 push. agent 가 chartmuseum 도달 불가한
		// 환경에서도 install 성공. blob 이 4 MB gRPC default frame 을 넘을 수 있어 AgentRuntimeEndpoint 의
		// maxInboundMessageSize 16 MB 까지 확장됨.
		p.put("chartTarballBase64", chartTarballBase64 == null ? "" : chartTarballBase64);
		p.put("version", version == null ? "" : version);
		p.put("namespace", namespace == null ? "" : namespace);
		p.put("values", valuesJson == null ? "" : valuesJson);
		p.put("createNamespace", String.valueOf(createNamespace));
		Struct params = struct(p);
		return send(clusterName, CommandType.INSTALL_ADDON, params, HELM_OPERATION_TIMEOUT_SECONDS);
	}

	/**
	 * UPGRADE_ADDON — agent 측 helm upgrade. install 과 동일 allowlist 검증 + release lock 공유.
	 *
	 * @param atomic       실패 시 자동 rollback (helm CLI 의 --atomic).
	 * @param reuseValues  기존 release 의 values 보존 + 새 values merge (helm CLI 의 --reuse-values).
	 * @param resetValues  기존 values 모두 reset, chart default 만 사용 (helm CLI 의 --reset-values).
	 *                     reuseValues 와 mutually exclusive — resetValues 우선.
	 * @param timeoutSec   timeout (초). 0 이면 agent default (600s).
	 */
	public CompletableFuture<CommandResponse> upgradeAddon(String clusterName, String releaseName,
			String chart, String repoUrl, String chartTarballBase64, String version,
			String namespace, String valuesJson, boolean atomic, boolean reuseValues,
			boolean resetValues, int timeoutSec) {
		java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
		p.put("release", releaseName == null ? "" : releaseName);
		p.put("chart", chart == null ? "" : chart);
		p.put("repoUrl", repoUrl == null ? "" : repoUrl);
		p.put("chartTarballBase64", chartTarballBase64 == null ? "" : chartTarballBase64);
		p.put("version", version == null ? "" : version);
		p.put("namespace", namespace == null ? "" : namespace);
		p.put("values", valuesJson == null ? "" : valuesJson);
		p.put("atomic", String.valueOf(atomic));
		p.put("reuseValues", String.valueOf(reuseValues));
		p.put("resetValues", String.valueOf(resetValues));
		if (timeoutSec > 0) {
			p.put("timeout", String.valueOf(timeoutSec));
		}
		Struct params = struct(p);
		// Helm upgrade 는 install 보다 더 긴 시간 (DB migration hook 등) — service-level timeout 도
		// HELM_OPERATION_TIMEOUT_SECONDS 사용.
		return send(clusterName, CommandType.UPGRADE_ADDON, params, HELM_OPERATION_TIMEOUT_SECONDS);
	}

	/**
	 * UNINSTALL_ADDON — agent 측 Helm uninstall. AllowList 의 namespace 검증만 적용.
	 *
	 * @param keepHistory CLI 의 {@code --keep-history} 등가. true 면 helm revision 이력 보존.
	 * @param wait        CLI 의 {@code --wait} 등가. true 면 모든 자원 삭제될 때까지 대기.
	 */
	public CompletableFuture<CommandResponse> uninstallAddon(String clusterName, String releaseName,
			String namespace, boolean keepHistory, boolean wait) {
		Struct params = struct(Map.of(
				"release", releaseName == null ? "" : releaseName,
				"namespace", namespace == null ? "" : namespace,
				"keepHistory", String.valueOf(keepHistory),
				"wait", String.valueOf(wait)));
		return send(clusterName, CommandType.UNINSTALL_ADDON, params, HELM_OPERATION_TIMEOUT_SECONDS);
	}

	/** LIST_HELM_RELEASES — agent 측 `helm list` 등가. namespace 가 "_all" 또는 빈 문자열이면 모든 ns. */
	public CompletableFuture<CommandResponse> listHelmReleases(String clusterName, String namespace) {
		Struct params = struct(Map.of("namespace", namespace == null ? "" : namespace));
		return send(clusterName, CommandType.LIST_HELM_RELEASES, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * LIST_HELM_RELEASE_RESOURCES — release 의 K8s 자원 enumerate (Deployment/Service/Secret/PVC/…).
	 * backend 의 HelmReleaseScanner 의 agent-side 대체.
	 */
	public CompletableFuture<CommandResponse> listHelmReleaseResources(String clusterName,
			String namespace, String release) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"release", release == null ? "" : release));
		return send(clusterName, CommandType.LIST_HELM_RELEASE_RESOURCES, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * GET_HELM_RELEASE_STATUS — 단일 release 의 현재 status (helm SDK action.NewStatus 등가).
	 * 결과 struct 에 name/namespace/chart/version/revision/status/updated.
	 * release 미존재면 agent error_code=HELM_NOT_FOUND.
	 */
	public CompletableFuture<CommandResponse> getHelmReleaseStatus(String clusterName,
			String namespace, String release) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"release", release == null ? "" : release));
		return send(clusterName, CommandType.GET_HELM_RELEASE_STATUS, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * GET_HELM_RELEASE_HISTORY — release 의 revision 이력. {@code max} 가 0 이면 helm 기본(10).
	 * 응답에 {@code revisions} list (각 {revision, updated, status, chart, app_version, description}).
	 */
	public CompletableFuture<CommandResponse> getHelmReleaseHistory(String clusterName,
			String namespace, String release, int max) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"release", release == null ? "" : release,
				"max", String.valueOf(max)));
		return send(clusterName, CommandType.GET_HELM_RELEASE_HISTORY, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * ROLLBACK_HELM_RELEASE — release 를 지정 revision 으로 복원. {@code revision} 이 0 이면 helm
	 * 의 default (직전 성공 revision). rollback 후 status 호출 결과를 응답에 같이 담음.
	 */
	public CompletableFuture<CommandResponse> rollbackHelmRelease(String clusterName,
			String namespace, String release, int revision, boolean wait) {
		Struct params = struct(Map.of(
				"namespace", namespace == null ? "" : namespace,
				"release", release == null ? "" : release,
				"revision", String.valueOf(revision),
				"wait", String.valueOf(wait)));
		return send(clusterName, CommandType.ROLLBACK_HELM_RELEASE, params, HELM_OPERATION_TIMEOUT_SECONDS);
	}

	/**
	 * LIST_RESOURCE_KINDS — agent 가 discovery API 로 enumerate 한 모든 kind. UI 의 "kind picker"
	 * 데이터 소스. params 없음. 응답 result.data 에 {@code kinds: [{plural, singular, kind, group,
	 * version, namespaced, short_names}, ...]}.
	 */
	public CompletableFuture<CommandResponse> listResourceKinds(String clusterName) {
		Struct params = Struct.getDefaultInstance();
		return send(clusterName, CommandType.LIST_RESOURCE_KINDS, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * RESOLVE_RESOURCE — 사용자 입력 (short name / plural / Kind 등) 을 정규화. y 의 list 와 보완.
	 * params: {@code input}. 응답 OK: ResolvedResource fields. 실패 시 INVALID_PARAMS +
	 * error_code=UNSUPPORTED_KIND + result.data.suggestions.
	 */
	public CompletableFuture<CommandResponse> resolveResource(String clusterName, String input) {
		Struct params = struct(Map.of("input", input == null ? "" : input));
		return send(clusterName, CommandType.RESOLVE_RESOURCE, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * GET_AGENT_CONFIG — agent 의 현재 in-memory allowlist + resource_policy snapshot.
	 * UI 운영자 페이지가 적용된 policy 시각화 + ConfigMap edit 후 reload 검증용. params 없음.
	 */
	public CompletableFuture<CommandResponse> getAgentConfig(String clusterName) {
		Struct params = Struct.getDefaultInstance();
		return send(clusterName, CommandType.GET_AGENT_CONFIG, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * ENSURE_AGENT_CONFIG_ANNOTATIONS — agent 의 ConfigMap 에 {@code helm.sh/resource-policy: keep}
	 * annotation 만 추가 (data 미변경). 멱등 — 이미 있으면 no-op. Backend startup hook 에서 모든 ACTIVE
	 * cluster 의 legacy ConfigMap 을 backfill 할 때 사용.
	 */
	public CompletableFuture<CommandResponse> ensureAgentConfigAnnotations(String clusterName) {
		Struct params = Struct.getDefaultInstance();
		return send(clusterName, CommandType.ENSURE_AGENT_CONFIG_ANNOTATIONS, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * APPLY_AGENT_CONFIG — backend 가 새 allowlist + resource_policy snapshot 을 agent 의 ConfigMap
	 * ({@code aipaas-agent-allowlist}) 으로 push. agent 가 K8s API 로 update → 기존 ConfigMap watch 가
	 * 자동 reload. backend 에는 DB 저장 안 함 (ConfigMap 이 single source of truth).
	 *
	 * <p>4개 JSON array string + 1개 YAML string 파라미터 — agent dispatcher 가 parse 후 write.
	 *
	 * @param allowedNamespacesJson      JSON array string. 예: {@code ["*"]} or {@code ["monitoring","app"]}
	 * @param allowedCommandsJson        JSON array string
	 * @param allowedChartsJson          JSON array string
	 * @param allowedExecNamespacesJson  JSON array string (옵션 — null 가능). null 이면 빈 array 로 전송.
	 * @param resourcePolicyYaml         resource_policy section 의 YAML 문자열 (옵션 — null 또는 blank 이면
	 *                                   ConfigMap 의 resource_policy 키가 비어 있게 됨, legacy 동작)
	 */
	public CompletableFuture<CommandResponse> applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml) {
		return applyAgentConfig(clusterName, allowedNamespacesJson, allowedCommandsJson,
				allowedChartsJson, allowedExecNamespacesJson, resourcePolicyYaml, null);
	}

	/**
	 * Hybrid helm-repo sync overload. {@code helmRepositoriesJson} 가
	 * JSON array of objects: {@code [{"name":"...","url":"...","username":"...","password":"...",
	 * "ca_file":"...","insecure_skip_tls_verify":false}, ...]}. null 이면 빈 array — agent 측에서
	 * "사용자가 명시적으로 비움" 으로 해석해 모든 등록 repo 를 unregister.
	 *
	 * <p>backend 의 helm_repo 테이블 변경 시 호출자 (PolicyService 등) 가 본 변경된 list 를 같이
	 * 직렬화해 보냄. agent 의 reconciler 가 ConfigMap watch → helm SDK RepositoryFile 갱신.
	 */
	public CompletableFuture<CommandResponse> applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml,
			String helmRepositoriesJson) {
		return applyAgentConfig(clusterName, allowedNamespacesJson, allowedCommandsJson, allowedChartsJson,
				allowedExecNamespacesJson, resourcePolicyYaml, helmRepositoriesJson, null);
	}

	/**
	 * Fleet-wide OidcGroupBinding 동기 overload. {@code oidcBindingsJson} 가
	 * JSON array of objects: {@code [{"name":"...","spec":{...}}, ...]}. 형식은
	 * operator 의 OidcGroupBindingSpec 와 1:1.
	 *
	 * <p>null 이면 빈 array — agent 가 모든 등록 binding 을 제거 (operator 가 cleanup).
	 *
	 * <p>backward compat: 새 agent 만 처리. 옛 agent 는 unknown param key 무시 (silent noop).
	 */
	public CompletableFuture<CommandResponse> applyAgentConfig(String clusterName,
			String allowedNamespacesJson, String allowedCommandsJson, String allowedChartsJson,
			String allowedExecNamespacesJson, String resourcePolicyYaml,
			String helmRepositoriesJson, String oidcBindingsJson) {
		Map<String, String> p = new java.util.LinkedHashMap<>();
		p.put("allowed_namespaces", allowedNamespacesJson == null ? "[]" : allowedNamespacesJson);
		p.put("allowed_commands", allowedCommandsJson == null ? "[]" : allowedCommandsJson);
		p.put("allowed_charts", allowedChartsJson == null ? "[]" : allowedChartsJson);
		p.put("allowed_exec_namespaces", allowedExecNamespacesJson == null ? "[]" : allowedExecNamespacesJson);
		p.put("resource_policy", resourcePolicyYaml == null ? "" : resourcePolicyYaml);
		p.put("helm_repositories", helmRepositoriesJson == null ? "[]" : helmRepositoriesJson);
		p.put("oidc_bindings", oidcBindingsJson == null ? "[]" : oidcBindingsJson);
		Struct params = struct(p);
		return send(clusterName, CommandType.APPLY_AGENT_CONFIG, params, DEFAULT_TIMEOUT_SECONDS);
	}

	/** Generic dispatch — 미래의 CommandType 추가 시 공통 사용. */
	public CompletableFuture<CommandResponse> send(String clusterName, CommandType type, Struct params,
			int timeoutSeconds) {
		CommandRequest.Builder cmdBuilder = CommandRequest.newBuilder()
				.setType(type)
				.setParams(params)
				.setTimeoutSeconds(timeoutSeconds);
		applyImpersonation(cmdBuilder, clusterName, type);
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(cmdBuilder.build());
		log.debug("dispatch command cluster={} type={} timeout={}s", clusterName, type, timeoutSeconds);
		return sessionRegistry.sendCommand(clusterName, builder, timeoutSeconds);
	}

	/**
	 * ImpersonationContext.current() 를 읽어 CommandRequest 의 impersonate_* 필드에 매핑. holder 가
	 * 비었거나 user 가 blank 이면 no-op.
	 */
	private void applyImpersonation(CommandRequest.Builder cmdBuilder, String clusterName, CommandType type) {
		if (impersonationContextProvider == null) return;
		ImpersonationContext ctx = impersonationContextProvider.getIfAvailable();
		if (ctx == null) return;
		Optional<ImpersonationIdentity> currentOpt = ctx.current();
		if (currentOpt.isEmpty()) return;

		ImpersonationIdentity id = currentOpt.get();
		cmdBuilder.setImpersonateUser(id.user());
		List<String> groups = id.groups();
		if (groups != null && !groups.isEmpty()) {
			cmdBuilder.addAllImpersonateGroups(groups);
		}
		Map<String, List<String>> extras = id.extras();
		if (extras != null && !extras.isEmpty()) {
			for (Map.Entry<String, List<String>> e : extras.entrySet()) {
				ImpersonateExtra.Builder eb = ImpersonateExtra.newBuilder();
				if (e.getValue() != null) eb.addAllValues(e.getValue());
				cmdBuilder.putImpersonateExtras(e.getKey(), eb.build());
			}
		}
		// debug 만 — production log 노이즈 방지. id 자체는 audit log layer 에서 별도 기록.
		log.debug("dispatch with impersonation cluster={} type={} user={} groups={}",
				clusterName, type, id.user(), groups);
	}

	private static Struct struct(Map<String, String> entries) {
		Struct.Builder b = Struct.newBuilder();
		entries.forEach((k, v) -> b.putFields(k, Value.newBuilder().setStringValue(v).build()));
		return b.build();
	}
}
