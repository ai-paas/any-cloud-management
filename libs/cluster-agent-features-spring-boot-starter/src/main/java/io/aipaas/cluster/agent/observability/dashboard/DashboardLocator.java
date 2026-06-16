package io.aipaas.cluster.agent.observability.dashboard;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.core.DashboardLocation;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Grafana 의 외부 접근 URL 조회.
 *
 * <p>frontend 가 iframe / link 로 사용. 기본은 Ingress > LoadBalancer > (실패) 순.
 */
@Slf4j
@RequiredArgsConstructor
public class DashboardLocator {

	private final AgentSessionRegistry sessionRegistry;
	private final Duration timeout;

	public DashboardLocation locate(String clusterName, String namespace, String serviceName) {
		Struct params = Struct.newBuilder()
				.putFields("namespace", strVal(namespace == null ? "monitoring" : namespace))
				.putFields("service_name", strVal(
						serviceName == null ? "kube-prometheus-stack-grafana" : serviceName))
				.build();

		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(CommandType.GET_DASHBOARD_URL)
						.setParams(params)
						.setTimeoutSeconds((int) timeout.getSeconds())
						.build());

		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) timeout.getSeconds())
					.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				throw new ObservabilityException(
						resp.getErrorCode().isEmpty() ? "DASHBOARD_LOOKUP_FAILED" : resp.getErrorCode(),
						resp.getErrorMessage());
			}
			Map<String, Value> fields = resp.getResult().getFieldsMap();
			return new DashboardLocation(
					clusterName,
					readString(fields, "url"),
					readString(fields, "host"),
					(int) readNumber(fields, "port"),
					readString(fields, "exposure"));
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			throw new ObservabilityException("NO_ACTIVE_AGENT",
					"no active agent stream for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			throw new ObservabilityException("TIMEOUT",
					"dashboard lookup timeout (cluster=" + clusterName + ")", e);
		} catch (ExecutionException e) {
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

	private static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s == null ? "" : s).build();
	}

	private static String readString(Map<String, Value> fields, String key) {
		Value v = fields.get(key);
		return v == null ? null : v.getStringValue();
	}

	private static double readNumber(Map<String, Value> fields, String key) {
		Value v = fields.get(key);
		return v == null ? 0 : v.getNumberValue();
	}
}
