package io.aipaas.cluster.agent.observability.stack;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 특정 helm release 가 이미 설치되어 있는지 확인하는 helper.
 *
 * <p>Auto-installer 가 install 호출 전 본 check 로 멱등성 보장 — 이미 설치된 cluster 에 재시도 시
 * "release already exists" 에러로 stream 에 noise 발생하지 않음.
 *
 * <p>내부적으로 LIST_HELM_RELEASES 명령 dispatch + 응답 release 배열에서 (namespace, name) 매칭.
 *
 * <p>실패는 swallow — caller (auto-installer) 가 이 결과 못 받으면 false 로 treat 해서 install 시도.
 * 그 결과가 진짜 에러면 install 측 에서 처리.
 */
@Slf4j
@RequiredArgsConstructor
public class HelmReleaseLookup {

	private final AgentSessionRegistry sessionRegistry;
	private final Duration timeout;

	/**
	 * (namespace, release) 가 cluster 에 이미 deployed 상태로 존재하는지.
	 *
	 * @return true 면 설치되어 있음. false 면 없음 또는 조회 실패 (caller 는 install 진행).
	 */
	public boolean isInstalled(String clusterName, String namespace, String releaseName) {
		Struct params = Struct.newBuilder()
				.putFields("namespace", Value.newBuilder().setStringValue(namespace).build())
				.build();
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(CommandType.LIST_HELM_RELEASES)
						.setParams(params)
						.setTimeoutSeconds((int) timeout.getSeconds())
						.build());

		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) timeout.getSeconds())
					.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				log.debug("HelmReleaseLookup: agent returned non-OK status={} for cluster={}, treating as not-installed",
						resp.getStatus(), clusterName);
				return false;
			}
			Value releasesVal = resp.getResult().getFieldsMap().get("releases");
			if (releasesVal == null || !releasesVal.hasListValue()) {
				return false;
			}
			ListValue releases = releasesVal.getListValue();
			for (Value rv : releases.getValuesList()) {
				if (!rv.hasStructValue()) {
					continue;
				}
				Struct rel = rv.getStructValue();
				String ns = getString(rel, "namespace");
				String name = getString(rel, "name");
				String status = getString(rel, "status");
				if (namespace.equals(ns) && releaseName.equals(name) && isHealthyStatus(status)) {
					return true;
				}
			}
			return false;
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			// 세션 없음 — caller 가 install 호출하면 동일 에러로 다시 보고하므로 본 helper 는 false.
			throw new ObservabilityException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			log.warn("HelmReleaseLookup: timeout for cluster={}, treating as not-installed", clusterName);
			return false;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException
					|| cause instanceof AgentSessionRegistry.SessionClosedException) {
				throw new ObservabilityException("NO_ACTIVE_AGENT",
						"agent session unavailable for cluster " + clusterName, cause);
			}
			log.warn("HelmReleaseLookup: ExecutionException cluster={}: {}", clusterName, e.toString());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ObservabilityException("INTERRUPTED", "interrupted", e);
		}
	}

	private static String getString(Struct s, String key) {
		Value v = s.getFieldsMap().get(key);
		return v == null ? null : v.getStringValue();
	}

	/** helm release status 중 "installed" 으로 간주할 status — deployed / superseded 는 OK. */
	private static boolean isHealthyStatus(String status) {
		if (status == null) {
			return false;
		}
		// helm 3 status: unknown, deployed, uninstalled, superseded, failed, uninstalling,
		// pending-install, pending-upgrade, pending-rollback. 보통 운영 중인 release 는 deployed.
		return "deployed".equalsIgnoreCase(status) || "superseded".equalsIgnoreCase(status);
	}
}
