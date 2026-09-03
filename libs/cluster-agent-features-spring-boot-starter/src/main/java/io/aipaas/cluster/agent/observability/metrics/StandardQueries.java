package io.aipaas.cluster.agent.observability.metrics;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;

import java.util.List;

/**
 * Cluster Observability 표준 PromQL 쿼리 모음 — kube-prometheus-stack / node-exporter 의 metric
 * 이름 기준. 호스트가 다른 stack 을 쓰면 별도 {@link io.aipaas.cluster.agent.observability.port.ClusterCatalog}
 * 와 함께 override 권장 (현재는 hardcoded).
 *
 * <p>각 query 는 두 방식으로 노출:
 * <ul>
 *   <li>도메인 헬퍼 메서드 ({@link #nodeCpuUsage(String)} 등) — 내부 호출용</li>
 *   <li>{@link #catalog()} — 모든 표준 query 의 id/label/promql/window 메타 (REST 노출용)</li>
 * </ul>
 *
 * <p>모든 placeholder {@code {{window}}} 는 {@code rate(...[5m])} 같은 PromQL window 로 치환.
 */
public final class StandardQueries {

	private StandardQueries() {}

	// ----- 도메인 헬퍼 (window 가 인자 — 내부 호출) -----

	/** Node 별 CPU 사용률 (0~1 비율, idle 제외). vector by node. */
	public static String nodeCpuUsage(String window) {
		return NODE_CPU_USAGE.render(window);
	}

	/** Namespace 별 CPU 사용 (cores). vector by namespace. */
	public static String namespaceCpuUsage(String window) {
		return NAMESPACE_CPU_USAGE.render(window);
	}

	/** Node 별 메모리 사용 bytes (used = total - available). vector by node. */
	public static String nodeMemoryUsage() {
		return NODE_MEMORY_USAGE.render(null);
	}

	/** Namespace 별 메모리 사용 bytes. vector by namespace. */
	public static String namespaceMemoryUsage() {
		return NAMESPACE_MEMORY_USAGE.render(null);
	}

	/** Pod phase 분포 (Running/Pending/Failed/Succeeded/Unknown). vector by phase. */
	public static String podCountByPhase() {
		return POD_COUNT_BY_PHASE.render(null);
	}

	/** Node Ready 상태 (1=Ready, 0=NotReady). vector by node. */
	public static String nodeReadyStatus() {
		return NODE_READY_STATUS.render(null);
	}

	/** TopK 노드 CPU. vector size <= k. */
	public static String topKNodesByCpu(int k, String window) {
		return "topk(" + k + ", " + nodeCpuUsage(window) + ")";
	}

	/** TopK 노드 메모리. vector size <= k. */
	public static String topKNodesByMemory(int k) {
		return "topk(" + k + ", " + nodeMemoryUsage() + ")";
	}

	// ----- 카탈로그 (REST 노출용) -----

	/** 표준 query 카탈로그 — 호스트가 그대로 클라이언트로 forward 하거나 편집 baseline 으로 사용. */
	public static List<StandardQuery> catalog() {
		return CATALOG;
	}

	// ----- 내부 상수 -----

	private static final StandardQuery NODE_CPU_USAGE = new StandardQuery(
			"node_cpu_usage",
			"Node 별 CPU 사용률",
			"0~1 비율, idle 제외. vector by node.",
			"sum by (node) (label_replace("
					+ "rate(node_cpu_seconds_total{mode!=\"idle\"}[{{window}}]),"
					+ "\"node\",\"$1\",\"instance\",\"(.+)\"))",
			true);

	private static final StandardQuery NAMESPACE_CPU_USAGE = new StandardQuery(
			"namespace_cpu_usage",
			"Namespace 별 CPU 사용",
			"cores. vector by namespace.",
			"sum by (namespace) (rate(container_cpu_usage_seconds_total[{{window}}]))",
			true);

	private static final StandardQuery NODE_MEMORY_USAGE = new StandardQuery(
			"node_memory_usage",
			"Node 별 메모리 사용",
			"bytes. used = MemTotal - MemAvailable. vector by node.",
			"sum by (node) (label_replace("
					+ "node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes,"
					+ "\"node\",\"$1\",\"instance\",\"(.+)\"))",
			false);

	private static final StandardQuery NAMESPACE_MEMORY_USAGE = new StandardQuery(
			"namespace_memory_usage",
			"Namespace 별 메모리 사용",
			"bytes. vector by namespace.",
			"sum by (namespace) (container_memory_usage_bytes{namespace!=\"\"})",
			false);

	private static final StandardQuery POD_COUNT_BY_PHASE = new StandardQuery(
			"pod_count_by_phase",
			"Pod phase 분포",
			"Running/Pending/Failed/Succeeded/Unknown 별 count. vector by phase.",
			"sum by (phase) (kube_pod_status_phase)",
			false);

	private static final StandardQuery NODE_READY_STATUS = new StandardQuery(
			"node_ready_status",
			"Node Ready 상태",
			"1=Ready, 0=NotReady. vector by node.",
			"kube_node_status_condition{condition=\"Ready\",status=\"true\"}",
			false);

	private static final StandardQuery TOPK_NODES_BY_CPU = new StandardQuery(
			"topk_nodes_by_cpu",
			"TopK Node CPU",
			"k 와 window 는 caller 가 선택. 본 entry 는 k=5 예시.",
			"topk(5, " + NODE_CPU_USAGE.promql() + ")",
			true);

	private static final StandardQuery TOPK_NODES_BY_MEMORY = new StandardQuery(
			"topk_nodes_by_memory",
			"TopK Node 메모리",
			"k 는 caller 가 선택. 본 entry 는 k=5 예시.",
			"topk(5, " + NODE_MEMORY_USAGE.promql() + ")",
			false);

	private static final List<StandardQuery> CATALOG = List.of(
			NODE_CPU_USAGE,
			NAMESPACE_CPU_USAGE,
			NODE_MEMORY_USAGE,
			NAMESPACE_MEMORY_USAGE,
			POD_COUNT_BY_PHASE,
			NODE_READY_STATUS,
			TOPK_NODES_BY_CPU,
			TOPK_NODES_BY_MEMORY);
}
