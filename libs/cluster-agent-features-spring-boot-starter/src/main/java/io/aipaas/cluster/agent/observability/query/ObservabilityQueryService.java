package io.aipaas.cluster.agent.observability.query;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.core.AlertSilenceResult;
import io.aipaas.cluster.agent.observability.core.AlertsResult;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import io.aipaas.cluster.agent.observability.core.MetricTargetsResult;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** PromQL/targets/alerts 쿼리를 cluster-agent gRPC 로 dispatch. queryAll = 카탈로그 fan-out (병렬). */
@Slf4j
@RequiredArgsConstructor
public class ObservabilityQueryService {

	private final AgentSessionRegistry sessionRegistry;
	private final ClusterCatalog catalog;
	private final Duration defaultTimeout;

	/** 단일 cluster 의 PromQL instant 쿼리. */
	public PromQLResult queryInstant(String clusterName, String promql, String time, Duration timeout) {
		return queryInstant(clusterName, promql, time, timeout, null);
	}

	/**
	 * extraParams overload. Prometheus HTTP API 의 optional param 들을 raw passthrough (timeout,
	 * limit, lookback_delta, stats). null 또는 빈 map 이면 기본 동작.
	 *
	 * <p>caller (raw query endpoint) 가 frontend 가 보낸 query string 을 그대로 전달 — backend 가
	 * Prometheus param 의미를 알 필요 없음.
	 */
	public PromQLResult queryInstant(String clusterName, String promql, String time, Duration timeout,
			Map<String, String> extraParams) {
		Map<String, String> p = new java.util.LinkedHashMap<>();
		p.put("query", promql);
		p.put("time", time == null ? "" : time);
		appendExtras(p, extraParams);
		Struct params = struct(p);
		CommandResponse resp = dispatch(clusterName, CommandType.QUERY_METRICS, params, timeout);
		return toPromQLResult(clusterName, resp);
	}

	/** 단일 cluster 의 PromQL range 쿼리. */
	public PromQLResult queryRange(String clusterName, String promql, String start, String end,
			String step, Duration timeout) {
		return queryRange(clusterName, promql, start, end, step, timeout, null);
	}

	/** extraParams overload. {@link #queryInstant} 와 동일. */
	public PromQLResult queryRange(String clusterName, String promql, String start, String end,
			String step, Duration timeout, Map<String, String> extraParams) {
		Map<String, String> p = new java.util.LinkedHashMap<>();
		p.put("query", promql);
		p.put("start", start);
		p.put("end", end);
		p.put("step", step);
		appendExtras(p, extraParams);
		Struct params = struct(p);
		CommandResponse resp = dispatch(clusterName, CommandType.QUERY_METRICS, params, timeout);
		return toPromQLResult(clusterName, resp);
	}

	/** extraParams 의 non-empty entry 만 params 에 추가 — Prometheus 기본값과 충돌 회피. */
	private static void appendExtras(Map<String, String> base, Map<String, String> extras) {
		if (extras == null) {
			return;
		}
		for (Map.Entry<String, String> e : extras.entrySet()) {
			if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
				base.put(e.getKey(), e.getValue());
			}
		}
	}

	/** 카탈로그 fan-out PromQL instant — 실패 cluster 는 result map 에서 누락 (warn 로깅). */
	public Map<String, PromQLResult> queryAll(String promql, Duration perClusterTimeout) {
		List<String> clusters = catalog.listClusterNames();
		Map<String, CompletableFuture<PromQLResult>> futures = new LinkedHashMap<>();
		for (String cluster : clusters) {
			futures.put(cluster, CompletableFuture.supplyAsync(() ->
					queryInstant(cluster, promql, null, perClusterTimeout)));
		}
		Map<String, PromQLResult> out = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();
		for (Map.Entry<String, CompletableFuture<PromQLResult>> e : futures.entrySet()) {
			try {
				out.put(e.getKey(),
						e.getValue().get(perClusterTimeout.toMillis() + 2000, TimeUnit.MILLISECONDS));
			} catch (Exception ex) {
				errors.add(e.getKey() + ": " + ex.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			log.warn("queryAll: partial failures — {}", errors);
		}
		return out;
	}

	/** Prometheus targets 조회. */
	public MetricTargetsResult listTargets(String clusterName, String state, Duration timeout) {
		Struct params = struct(Map.of("state", state == null ? "" : state));
		CommandResponse resp = dispatch(clusterName, CommandType.LIST_METRIC_TARGETS, params, timeout);
		Map<String, Value> fields = resp.getResult().getFieldsMap();
		return new MetricTargetsResult(
				clusterName,
				readString(fields, "prometheus_url"),
				readString(fields, "raw"));
	}

	/** Alertmanager 의 활성 alert 조회. */
	public AlertsResult listAlerts(String clusterName, Duration timeout) {
		CommandResponse resp = dispatch(clusterName, CommandType.LIST_ALERTS, struct(Map.of()), timeout);
		Map<String, Value> fields = resp.getResult().getFieldsMap();
		return new AlertsResult(
				clusterName,
				readString(fields, "alertmanager_url"),
				readString(fields, "raw"));
	}

	/**
	 * Alertmanager silences 목록.
	 * 응답의 raw JSON 은 Alertmanager /api/v2/silences 의 array (id, status, matchers, ...).
	 */
	public AlertsResult listAlertSilences(String clusterName, Duration timeout) {
		CommandResponse resp = dispatch(clusterName, CommandType.LIST_ALERT_SILENCES, struct(Map.of()), timeout);
		Map<String, Value> fields = resp.getResult().getFieldsMap();
		return new AlertsResult(
				clusterName,
				readString(fields, "alertmanager_url"),
				readString(fields, "raw"));
	}

	/**
	 * Alertmanager silence 생성. matchersJson 은 Alertmanager schema 의
	 * {@code [{name, value, isRegex, isEqual}, ...]} JSON array string.
	 *
	 * @return silence_id (Alertmanager 발급 UUID), alertmanager_url
	 */
	public AlertSilenceResult createAlertSilence(String clusterName, String matchersJson,
			String startsAt, String endsAt, String createdBy, String comment, Duration timeout) {
		Map<String, String> params = new java.util.LinkedHashMap<>();
		params.put("matchers", matchersJson);
		params.put("startsAt", startsAt);
		params.put("endsAt", endsAt);
		params.put("createdBy", createdBy);
		params.put("comment", comment);
		CommandResponse resp = dispatch(clusterName, CommandType.CREATE_ALERT_SILENCE, struct(params), timeout);
		Map<String, Value> fields = resp.getResult().getFieldsMap();
		return new AlertSilenceResult(
				clusterName,
				readString(fields, "alertmanager_url"),
				readString(fields, "silence_id"),
				readString(fields, "raw"));
	}

	/** Alertmanager silence 삭제. */
	public boolean deleteAlertSilence(String clusterName, String silenceId, Duration timeout) {
		dispatch(clusterName, CommandType.DELETE_ALERT_SILENCE,
				struct(Map.of("silence_id", silenceId)), timeout);
		return true;
	}

	// ----- internal -----

	private CommandResponse dispatch(String clusterName, CommandType type, Struct params, Duration timeout) {
		Duration effective = timeout == null ? defaultTimeout : timeout;
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				io.aipaas.cluster.agent.v1.CommandRequest.newBuilder()
						.setType(type)
						.setParams(params)
						.setTimeoutSeconds((int) effective.getSeconds())
						.build());
		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) effective.getSeconds())
					.get(effective.toMillis() + 1000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				throw new ObservabilityException(
						resp.getErrorCode().isEmpty() ? "AGENT_ERROR" : resp.getErrorCode(),
						resp.getErrorMessage());
			}
			return resp;
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			// defensive — 현재 sendCommand 는 failed-future 반환이지만 동기 throw 경로 보존.
			throw new ObservabilityException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			throw new ObservabilityException("TIMEOUT",
					"timeout waiting for agent response (cluster=" + clusterName + ")", e);
		} catch (ExecutionException e) {
			// CompletableFuture wrap — cause 별 errorCode 매핑.
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException) {
				throw new ObservabilityException("NO_ACTIVE_AGENT",
						"no active agent stream for cluster " + clusterName, cause);
			}
			if (cause instanceof AgentSessionRegistry.SessionClosedException) {
				throw new ObservabilityException("NO_ACTIVE_AGENT",
						"agent stream closed mid-request (cluster=" + clusterName + ")", cause);
			}
			throw new ObservabilityException("AGENT_CALL_FAILED",
					cause == null ? e.toString() : cause.toString(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ObservabilityException("INTERRUPTED", "interrupted", e);
		}
	}

	private PromQLResult toPromQLResult(String cluster, CommandResponse resp) {
		Map<String, Value> fields = resp.getResult().getFieldsMap();
		return new PromQLResult(
				cluster,
				readString(fields, "prometheus_url"),
				fields.containsKey("is_range") && fields.get("is_range").getBoolValue(),
				readString(fields, "raw"));
	}

	private static Struct struct(Map<String, String> entries) {
		Struct.Builder b = Struct.newBuilder();
		entries.forEach((k, v) -> b.putFields(k, Value.newBuilder().setStringValue(v).build()));
		return b.build();
	}

	private static String readString(Map<String, Value> fields, String key) {
		Value v = fields.get(key);
		return v == null ? null : v.getStringValue();
	}
}
