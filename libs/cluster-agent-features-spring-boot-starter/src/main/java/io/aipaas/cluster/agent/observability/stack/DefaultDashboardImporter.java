package io.aipaas.cluster.agent.observability.stack;

import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * kube-prometheus-stack 설치 직후 표준 dashboard 자동 import.
 *
 * <p>메커니즘: kube-prometheus-stack 의 Grafana sidecar (`grafana-sc-dashboard`) 는 cluster 안의
 * ConfigMap 중 label `grafana_dashboard=1` 가진 것을 자동 발견해 Grafana 에 import. 본 importer 는
 * 그 형식의 ConfigMap 들을 APPLY_MANIFEST 로 cluster 에 푸쉬.
 *
 * <p>현재 포함되는 dashboard:
 * <ul>
 *   <li>aipaas-cluster-overview — node/pod/namespace 핵심 메트릭 요약</li>
 *   <li>aipaas-gpu-overview — dcgm-exporter 기반 GPU 사용률 (GPU cluster 만)</li>
 * </ul>
 *
 * <p>호출 시점: {@link MonitoringAutoInstaller} 가 stack install 성공 직후. GPU dashboard 는
 * {@link io.aipaas.cluster.agent.observability.core.ClusterCapabilities#hasGpuNodes} true 일 때만.
 */
@Slf4j
public class DefaultDashboardImporter {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final AgentSessionRegistry sessionRegistry;
	private final String namespace;

	public DefaultDashboardImporter(AgentSessionRegistry sessionRegistry, String namespace) {
		this.sessionRegistry = sessionRegistry;
		this.namespace = namespace == null || namespace.isBlank() ? "monitoring" : namespace;
	}

	/** Cluster overview dashboard (CPU/메모리/Pod count). */
	public void importClusterOverview(String clusterName) {
		applyDashboardConfigMap(clusterName, "aipaas-cluster-overview", CLUSTER_OVERVIEW_JSON);
	}

	/** GPU overview dashboard — dcgm-exporter 메트릭 기반. */
	public void importGpuOverview(String clusterName) {
		applyDashboardConfigMap(clusterName, "aipaas-gpu-overview", GPU_OVERVIEW_JSON);
	}

	/**
	 * 사용자 정의 dashboard import — {@link io.aipaas.cluster.agent.observability.core.DashboardSource} 의
	 * Dashboard 1 개에 해당. caller (auto-installer) 가 source list 순회하며 호출.
	 */
	public void importCustom(String clusterName, String name, String dashboardJson) {
		applyDashboardConfigMap(clusterName, name, dashboardJson);
	}

	private void applyDashboardConfigMap(String clusterName, String name, String dashboardJson) {
		String manifest = buildConfigMapManifest(name, namespace, dashboardJson);
		Struct params = Struct.newBuilder()
				.putFields("manifest", strVal(manifest))
				.putFields("namespace", strVal(namespace))
				.putFields("force", strVal("true"))     // 재import 안전 (server-side apply).
				.build();
		ControlMessage.Builder builder = ControlMessage.newBuilder().setCommand(
				CommandRequest.newBuilder()
						.setType(CommandType.APPLY_MANIFEST)
						.setParams(params)
						.setTimeoutSeconds((int) TIMEOUT.getSeconds())
						.build());
		try {
			CommandResponse resp = sessionRegistry
					.sendCommand(clusterName, builder, (int) TIMEOUT.getSeconds())
					.get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
			if (resp.getStatus() != Status.OK) {
				log.warn("Dashboard import failed cluster={} dashboard={} code={} msg={}",
						clusterName, name, resp.getErrorCode(), resp.getErrorMessage());
				return;
			}
			log.info("Dashboard imported cluster={} dashboard={}", clusterName, name);
		} catch (AgentSessionRegistry.NoActiveSessionException e) {
			throw new ObservabilityException("NO_ACTIVE_AGENT",
					"no active agent for cluster " + clusterName, e);
		} catch (TimeoutException e) {
			log.warn("Dashboard import timeout cluster={} dashboard={}", clusterName, name);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof AgentSessionRegistry.NoActiveSessionException
					|| cause instanceof AgentSessionRegistry.SessionClosedException) {
				throw new ObservabilityException("NO_ACTIVE_AGENT",
						"agent session unavailable", cause);
			}
			log.warn("Dashboard import error cluster={} dashboard={}: {}",
					clusterName, name, cause == null ? e.toString() : cause.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static Value strVal(String s) {
		return Value.newBuilder().setStringValue(s == null ? "" : s).build();
	}

	/**
	 * Grafana sidecar 자동 발견 형식 — ConfigMap with label grafana_dashboard=1, data 의 key 가
	 * dashboard 파일명.
	 */
	private static String buildConfigMapManifest(String name, String namespace, String dashboardJson) {
		// JSON 안의 newlines / quote 를 YAML literal block scalar (|) 로 안전하게 임베드.
		String indented = "    " + dashboardJson.replace("\n", "\n    ");
		return String.format("""
				apiVersion: v1
				kind: ConfigMap
				metadata:
				  name: %s
				  namespace: %s
				  labels:
				    grafana_dashboard: "1"
				    app.kubernetes.io/managed-by: aipaas-cluster-agent
				data:
				  %s.json: |
				%s
				""", name, namespace, name, indented);
	}

	// ============================================================================
	// Embedded dashboard JSON. minimal — Grafana 가 import 시 fields 보완. 본 starter 가
	// 외부 dashboard repo 없이 zero-config 로 즉시 동작하도록 inline.
	// 운영 환경에서는 호스트 애플리케이션이 별도 ConfigMap apply 로 더 풍부한 dashboard 추가 가능.
	// ============================================================================

	private static final String CLUSTER_OVERVIEW_JSON = """
			{
			  "title": "AIPaaS Cluster Overview",
			  "uid": "aipaas-cluster-overview",
			  "schemaVersion": 38,
			  "version": 1,
			  "timezone": "browser",
			  "refresh": "30s",
			  "time": {"from": "now-1h", "to": "now"},
			  "panels": [
			    {
			      "id": 1, "type": "stat", "title": "Ready Nodes",
			      "gridPos": {"x":0,"y":0,"w":6,"h":4},
			      "targets": [{"expr": "sum(kube_node_status_condition{condition=\\"Ready\\",status=\\"true\\"})"}]
			    },
			    {
			      "id": 2, "type": "stat", "title": "Running Pods",
			      "gridPos": {"x":6,"y":0,"w":6,"h":4},
			      "targets": [{"expr": "sum(kube_pod_status_phase{phase=\\"Running\\"})"}]
			    },
			    {
			      "id": 3, "type": "timeseries", "title": "Cluster CPU usage",
			      "gridPos": {"x":0,"y":4,"w":12,"h":8},
			      "targets": [{"expr": "sum(rate(container_cpu_usage_seconds_total{container!=\\"\\"}[5m]))"}]
			    },
			    {
			      "id": 4, "type": "timeseries", "title": "Cluster memory usage (bytes)",
			      "gridPos": {"x":12,"y":4,"w":12,"h":8},
			      "targets": [{"expr": "sum(container_memory_working_set_bytes{container!=\\"\\"})"}]
			    }
			  ]
			}
			""";

	private static final String GPU_OVERVIEW_JSON = """
			{
			  "title": "AIPaaS GPU Overview",
			  "uid": "aipaas-gpu-overview",
			  "schemaVersion": 38,
			  "version": 1,
			  "timezone": "browser",
			  "refresh": "30s",
			  "time": {"from": "now-1h", "to": "now"},
			  "panels": [
			    {
			      "id": 1, "type": "stat", "title": "GPU Nodes",
			      "gridPos": {"x":0,"y":0,"w":6,"h":4},
			      "targets": [{"expr": "count(count by (node)(DCGM_FI_DEV_GPU_UTIL))"}]
			    },
			    {
			      "id": 2, "type": "timeseries", "title": "GPU Utilization (%)",
			      "gridPos": {"x":0,"y":4,"w":24,"h":8},
			      "targets": [{"expr": "DCGM_FI_DEV_GPU_UTIL", "legendFormat": "{{instance}} GPU-{{gpu}}"}]
			    },
			    {
			      "id": 3, "type": "timeseries", "title": "GPU Memory Used (MiB)",
			      "gridPos": {"x":0,"y":12,"w":24,"h":8},
			      "targets": [{"expr": "DCGM_FI_DEV_FB_USED", "legendFormat": "{{instance}} GPU-{{gpu}}"}]
			    }
			  ]
			}
			""";
}
