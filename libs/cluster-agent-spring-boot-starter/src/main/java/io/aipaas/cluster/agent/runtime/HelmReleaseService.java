package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.SessionClosedException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Day-2 Helm ops 를 agent gRPC stream 으로 routing 하는 starter-side facade.
 * {@link KubeResourceService} 와 동일한 패턴 — backend 의 ChartServiceImpl 이 본 service 를
 * 우선 호출하고 실패/세션 없을 때 helm CLI + kubeconfig fallback.
 *
 * <p>지원: install / uninstall / listReleases. agent dispatcher 의 INSTALL_ADDON /
 * UNINSTALL_ADDON / LIST_HELM_RELEASES 와 1:1 매핑.
 *
 * <p>특성:
 * <ul>
 *   <li>agent 의 in-cluster Helm SDK 가 실제 실행 → chartmuseum 도 cluster 안에서 reach 가능해야 함</li>
 *   <li>AllowList enforcement: chart name + version range + namespace 모두 agent 가 검증 (deny-all default)</li>
 *   <li>session 없으면 isActiveFor=false → caller 가 helm CLI fallback 결정</li>
 * </ul>
 *
 * @see KubeResourceService K8s ops 의 동일 패턴
 */
@Slf4j
@RequiredArgsConstructor
public class HelmReleaseService {

	/** Helm install/uninstall 은 chart wait/hook 으로 분 단위 — 추가 cushion. */
	private static final long HELM_OPERATION_TIMEOUT_SECONDS = 300L + 15L;

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

	/**
	 * INSTALL_ADDON — agent in-cluster Helm SDK 로 chart 설치.
	 * AllowList 의 chart rule + namespace + version range 통과 필수.
	 *
	 * <p>Chart 다운로드 경로 우선순위:
	 * <ol>
	 *   <li>{@code chartTarballBase64} 명시 — backend 가 미리 fetch 해 push. agent 가 chartmuseum
	 *       도달 불가한 air-gapped / private 환경의 권장 경로.</li>
	 *   <li>{@code repoUrl} 명시 — agent 가 직접 다운로드 (URL 이 agent 에서 도달 가능해야).</li>
	 *   <li>둘 다 없음 — agent 의 {@code ~/.config/helm/repositories.yaml} alias 검색 (보통 실패).</li>
	 * </ol>
	 *
	 * @param chartTarballBase64 backend 가 pre-fetch 한 chart {@code .tgz} 의 base64 인코딩 (선택).
	 * @param repoUrl            chart repository URL (선택). chartTarballBase64 가 있으면 무시.
	 * @return 설치 결과 (release / namespace / chart / version / revision / status).
	 */
	public InstalledRelease install(String clusterName, String releaseName, String chart, String repoUrl,
			String chartTarballBase64, String version, String namespace, String valuesJson,
			boolean createNamespace) {
		CompletableFuture<CommandResponse> future = commandRouter.installAddon(
				clusterName, releaseName, chart, repoUrl, chartTarballBase64, version, namespace,
				valuesJson, createNamespace);
		CommandResponse resp = await(future, "INSTALL_ADDON", HELM_OPERATION_TIMEOUT_SECONDS);
		requireOk(resp, "INSTALL_ADDON");

		return InstalledRelease.fromStruct(resp.getResult());
	}

	/**
	 * UPGRADE_ADDON — 기존 release 를 새 chart version / values 로 업그레이드.
	 *
	 * <p>Install 과 동일 allowlist 검증 + release lock (agent 측). release 미존재 시
	 * {@code HELM_NOT_FOUND} error_code 로 회신 — caller 가 먼저 install 안내.
	 *
	 * @param atomic      실패 시 자동 rollback. 운영자 권장 (production 안정성).
	 * @param reuseValues 기존 release 의 values 보존 + 새 values merge.
	 * @param resetValues 기존 values 모두 reset (chart default + 새 values 만). reuseValues 우선 무시.
	 * @param timeoutSec  timeout (초). 0 이면 agent default (600s, helm hook 대비 install 보다 김).
	 * @return upgrade 결과 (release / namespace / chart / version / revision / status / updated).
	 */
	public InstalledRelease upgrade(String clusterName, String releaseName, String chart,
			String repoUrl, String chartTarballBase64, String version, String namespace,
			String valuesJson, boolean atomic, boolean reuseValues, boolean resetValues,
			int timeoutSec) {
		CompletableFuture<CommandResponse> future = commandRouter.upgradeAddon(
				clusterName, releaseName, chart, repoUrl, chartTarballBase64, version, namespace,
				valuesJson, atomic, reuseValues, resetValues, timeoutSec);
		CommandResponse resp = await(future, "UPGRADE_ADDON", HELM_OPERATION_TIMEOUT_SECONDS);
		requireOk(resp, "UPGRADE_ADDON");
		return InstalledRelease.fromStruct(resp.getResult());
	}

	/**
	 * UNINSTALL_ADDON — agent helm uninstall. namespace allowlist 통과 필수.
	 *
	 * @param keepHistory true 면 helm revision 이력 보존 ({@code --keep-history} 등가).
	 * @param wait        true 면 모든 자원 삭제될 때까지 대기 ({@code --wait} 등가).
	 * @return true 면 정상 (agent OK 응답). resource 없거나 partial 실패는 agent error_code 로.
	 */
	public boolean uninstall(String clusterName, String releaseName, String namespace,
			boolean keepHistory, boolean wait) {
		CompletableFuture<CommandResponse> future = commandRouter.uninstallAddon(
				clusterName, releaseName, namespace, keepHistory, wait);
		CommandResponse resp = await(future, "UNINSTALL_ADDON", HELM_OPERATION_TIMEOUT_SECONDS);
		requireOk(resp, "UNINSTALL_ADDON");
		return true;
	}

	/**
	 * LIST_HELM_RELEASE_RESOURCES — release 가 만든 K8s 자원 enumerate.
	 * backend 의 HelmReleaseScanner (fabric8 11 호출) 의 agent-side 대체. agent in-cluster
	 * 호출이라 latency 가 backend → API server 11 RTT 대비 우월.
	 *
	 * @return 자원 ref 목록 (kind, apiVersion, namespace, name).
	 */
	public java.util.List<HelmReleaseResource> listReleaseResources(String clusterName,
			String namespace, String release) {
		CompletableFuture<CommandResponse> future = commandRouter.listHelmReleaseResources(
				clusterName, namespace, release);
		CommandResponse resp = await(future, "LIST_HELM_RELEASE_RESOURCES", commandTimeoutSeconds + 5L);
		requireOk(resp, "LIST_HELM_RELEASE_RESOURCES");

		Value itemsField = resp.getResult().getFieldsOrDefault("items", null);
		if (itemsField == null || !itemsField.hasListValue()) {
			return java.util.List.of();
		}
		java.util.List<HelmReleaseResource> out = new java.util.ArrayList<>();
		for (Value v : itemsField.getListValue().getValuesList()) {
			if (!v.hasStructValue()) {
				continue;
			}
			com.google.protobuf.Struct s = v.getStructValue();
			out.add(new HelmReleaseResource(
					getString(s, "kind"),
					getString(s, "apiVersion"),
					getString(s, "namespace"),
					getString(s, "name")));
		}
		return out;
	}

	/** {@link #listReleaseResources} 결과의 단일 자원 ref. backend 가 DTO 매핑 시 사용. */
	public record HelmReleaseResource(String kind, String apiVersion, String namespace, String name) {}

	/**
	 * GET_HELM_RELEASE_STATUS — 단일 release 의 현재 status. backend 의 ChartServiceImpl.getChartStatus
	 * 의 agent-side path.
	 *
	 * @return release 의 chart/version/revision/status. agent error_code=HELM_NOT_FOUND 면 throw.
	 */
	public ReleaseStatus getStatus(String clusterName, String namespace, String release) {
		CompletableFuture<CommandResponse> future = commandRouter.getHelmReleaseStatus(
				clusterName, namespace, release);
		CommandResponse resp = await(future, "GET_HELM_RELEASE_STATUS", commandTimeoutSeconds + 5L);
		requireOk(resp, "GET_HELM_RELEASE_STATUS");
		return ReleaseStatus.fromStruct(resp.getResult());
	}

	/**
	 * GET_HELM_RELEASE_HISTORY — release 의 revision 이력. backend 의 ChartServiceImpl.getReleaseHistory
	 * 의 agent-side path.
	 *
	 * @param max <= 0 이면 helm default (10).
	 * @return revision list (newest first). release 미존재면 throw.
	 */
	public java.util.List<HistoryRevision> getHistory(String clusterName, String namespace,
			String release, int max) {
		CompletableFuture<CommandResponse> future = commandRouter.getHelmReleaseHistory(
				clusterName, namespace, release, max);
		CommandResponse resp = await(future, "GET_HELM_RELEASE_HISTORY", commandTimeoutSeconds + 5L);
		requireOk(resp, "GET_HELM_RELEASE_HISTORY");

		Value revsField = resp.getResult().getFieldsOrDefault("revisions", null);
		if (revsField == null || !revsField.hasListValue()) {
			return java.util.List.of();
		}
		java.util.List<HistoryRevision> out = new java.util.ArrayList<>();
		for (Value v : revsField.getListValue().getValuesList()) {
			if (!v.hasStructValue()) {
				continue;
			}
			com.google.protobuf.Struct s = v.getStructValue();
			out.add(new HistoryRevision(
					(int) getNumber(s, "revision"),
					getString(s, "updated"),
					getString(s, "status"),
					getString(s, "chart"),
					getString(s, "app_version"),
					getString(s, "description")));
		}
		return out;
	}

	/**
	 * ROLLBACK_HELM_RELEASE — release 를 지정 revision 으로 복원.
	 *
	 * @param revision 0 이면 helm 의 default (직전 성공 revision).
	 * @return rollback 후 status (chart/version/revision/status).
	 */
	public ReleaseStatus rollback(String clusterName, String namespace, String release,
			int revision, boolean wait) {
		CompletableFuture<CommandResponse> future = commandRouter.rollbackHelmRelease(
				clusterName, namespace, release, revision, wait);
		CommandResponse resp = await(future, "ROLLBACK_HELM_RELEASE", HELM_OPERATION_TIMEOUT_SECONDS);
		requireOk(resp, "ROLLBACK_HELM_RELEASE");
		return ReleaseStatus.fromStruct(resp.getResult());
	}

	/** {@link #getHistory} 결과의 단일 revision. backend 의 ChartHistoryItem 매핑 시 사용. */
	public record HistoryRevision(
			int revision,
			String updated,
			String status,
			String chart,
			String appVersion,
			String description) {}

	private static double getNumber(com.google.protobuf.Struct s, String key) {
		Value v = s.getFieldsOrDefault(key, null);
		return v == null ? 0 : v.getNumberValue();
	}

	/**
	 * GET_HELM_RELEASE_STATUS 결과. backend 의 ChartStatusResponse 매핑 시 사용.
	 * {@link InstalledRelease} 와 유사하지만 install 응답 전용 필드 (예: 직전 install 의 chart) 를
	 * 갖지 않고 status 조회용 read-only shape.
	 */
	public record ReleaseStatus(
			String release,
			String namespace,
			String chart,
			String version,
			String appVersion,
			int revision,
			String status,
			String updated) {

		static ReleaseStatus fromStruct(com.google.protobuf.Struct struct) {
			return new ReleaseStatus(
					getString(struct, "name"),
					getString(struct, "namespace"),
					getString(struct, "chart"),
					getString(struct, "version"),
					getString(struct, "app_version"),
					(int) getNumber(struct, "revision"),
					getString(struct, "status"),
					getString(struct, "updated"));
		}

		private static String getString(com.google.protobuf.Struct s, String key) {
			Value v = s.getFieldsOrDefault(key, null);
			return v == null ? "" : v.getStringValue();
		}

		private static double getNumber(com.google.protobuf.Struct s, String key) {
			Value v = s.getFieldsOrDefault(key, null);
			return v == null ? 0 : v.getNumberValue();
		}
	}

	private static String getString(com.google.protobuf.Struct s, String key) {
		Value v = s.getFieldsOrDefault(key, null);
		return v == null ? "" : v.getStringValue();
	}

	/**
	 * LIST_HELM_RELEASES — agent helm list. namespace 가 빈 문자열 또는 "_all" 이면 모든 ns.
	 * 응답은 release list 의 JSON 표현 (parse 책임은 caller).
	 */
	public JsonNode listReleases(String clusterName, String namespace) {
		CompletableFuture<CommandResponse> future = commandRouter.listHelmReleases(clusterName, namespace);
		CommandResponse resp = await(future, "LIST_HELM_RELEASES", commandTimeoutSeconds + 5L);
		requireOk(resp, "LIST_HELM_RELEASES");

		Value relList = resp.getResult().getFieldsOrDefault("releases", null);
		if (relList == null || !relList.hasListValue()) {
			throw new HelmRoutingException("Agent LIST_HELM_RELEASES response missing 'releases' list");
		}
		try {
			String json = JsonFormat.printer()
					.omittingInsignificantWhitespace()
					.print(com.google.protobuf.Value.newBuilder()
							.setListValue(relList.getListValue())
							.build());
			return objectMapper.readTree(json);
		} catch (Exception e) {
			throw new HelmRoutingException("Failed to parse LIST_HELM_RELEASES JSON: " + e.getMessage(), e);
		}
	}

	/* ---------- response shapes ---------- */

	/**
	 * agent 의 INSTALL_ADDON 응답 — dispatcher.installAddon 의 result struct 매핑.
	 * (release / namespace / chart / version / revision / status)
	 */
	public record InstalledRelease(
			String release,
			String namespace,
			String chart,
			String version,
			int revision,
			String status) {

		static InstalledRelease fromStruct(com.google.protobuf.Struct struct) {
			return new InstalledRelease(
					getString(struct, "release"),
					getString(struct, "namespace"),
					getString(struct, "chart"),
					getString(struct, "version"),
					(int) getNumber(struct, "revision"),
					getString(struct, "status"));
		}

		private static String getString(com.google.protobuf.Struct s, String key) {
			Value v = s.getFieldsOrDefault(key, null);
			return v == null ? "" : v.getStringValue();
		}

		private static double getNumber(com.google.protobuf.Struct s, String key) {
			Value v = s.getFieldsOrDefault(key, null);
			return v == null ? 0 : v.getNumberValue();
		}
	}

	/* ---------- internal ---------- */

	/** future 대기 + 모든 실패 유형을 {@link HelmRoutingException} 으로 통일. */
	private static CommandResponse await(CompletableFuture<CommandResponse> future,
			String opName, long timeoutSeconds) {
		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new HelmRoutingException("Agent " + opName + " timeout after " + timeoutSeconds + "s", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HelmRoutingException("Interrupted waiting for agent " + opName, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			if (cause instanceof NoActiveSessionException || cause instanceof SessionClosedException) {
				throw new HelmRoutingException("No active agent session: " + cause.getMessage(), cause);
			}
			throw new HelmRoutingException("Agent " + opName + " failed: " + cause.getMessage(), cause);
		}
	}

	private static void requireOk(CommandResponse resp, String opName) {
		if (resp.getStatus() != Status.OK) {
			throw new HelmRoutingException(
					"Agent " + opName + " returned " + resp.getStatus() + " (" + resp.getErrorCode() + "): "
							+ resp.getErrorMessage());
		}
	}
}
