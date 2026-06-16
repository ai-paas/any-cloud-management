package io.aipaas.cluster.agent.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import io.aipaas.cluster.agent.observability.query.ObservabilityQueryService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 고수준 Cluster metrics API — caller 가 PromQL 작성하지 않고 도메인 메서드 호출.
 *
 * <p>내부: {@link StandardQueries} 로 표준 PromQL 작성 → {@link ObservabilityQueryService#queryInstant}
 * → {@link MetricsResultParser} 로 typed {@link MetricSample} 변환.
 *
 * <p>모든 메서드는 instant snapshot. 시계열은 caller 가 {@link ObservabilityQueryService#queryRange} 직접.
 */
@RequiredArgsConstructor
public class ClusterMetricsService {

	private final ObservabilityQueryService queryService;
	private final ObjectMapper objectMapper;

	/** Node 별 CPU 사용률 (0~1, idle 제외). */
	public List<MetricSample> nodeCpuUsage(String clusterName, Duration window, Duration timeout) {
		String promql = StandardQueries.nodeCpuUsage(formatWindow(window));
		return run(clusterName, promql, timeout);
	}

	/** Namespace 별 CPU 사용 cores. */
	public List<MetricSample> namespaceCpuUsage(String clusterName, Duration window, Duration timeout) {
		String promql = StandardQueries.namespaceCpuUsage(formatWindow(window));
		return run(clusterName, promql, timeout);
	}

	/** Node 별 메모리 사용 bytes (used = total - available). */
	public List<MetricSample> nodeMemoryUsage(String clusterName, Duration timeout) {
		return run(clusterName, StandardQueries.nodeMemoryUsage(), timeout);
	}

	/** Namespace 별 메모리 사용 bytes. */
	public List<MetricSample> namespaceMemoryUsage(String clusterName, Duration timeout) {
		return run(clusterName, StandardQueries.namespaceMemoryUsage(), timeout);
	}

	/** Pod phase 분포 (Running/Pending/Failed/Succeeded/Unknown). */
	public List<MetricSample> podCountByPhase(String clusterName, Duration timeout) {
		return run(clusterName, StandardQueries.podCountByPhase(), timeout);
	}

	/** Node Ready 상태 (1=Ready). */
	public List<MetricSample> nodeReadyStatus(String clusterName, Duration timeout) {
		return run(clusterName, StandardQueries.nodeReadyStatus(), timeout);
	}

	/** TopK 노드 CPU 사용량. */
	public List<MetricSample> topKNodesByCpu(String clusterName, int k, Duration window, Duration timeout) {
		String promql = StandardQueries.topKNodesByCpu(k, formatWindow(window));
		return run(clusterName, promql, timeout);
	}

	/** TopK 노드 메모리 사용량. */
	public List<MetricSample> topKNodesByMemory(String clusterName, int k, Duration timeout) {
		return run(clusterName, StandardQueries.topKNodesByMemory(k), timeout);
	}

	// ----- internal -----

	private List<MetricSample> run(String clusterName, String promql, Duration timeout) {
		PromQLResult result = queryService.queryInstant(clusterName, promql, null, timeout);
		return MetricsResultParser.parse(result, objectMapper);
	}

	/** Duration → PromQL window 형식 ("5m", "30s"). */
	private static String formatWindow(Duration window) {
		if (window == null) return "5m";
		long sec = window.toSeconds();
		if (sec < 60) return sec + "s";
		long min = window.toMinutes();
		if (min < 60) return min + "m";
		long hours = window.toHours();
		return hours + "h";
	}
}
