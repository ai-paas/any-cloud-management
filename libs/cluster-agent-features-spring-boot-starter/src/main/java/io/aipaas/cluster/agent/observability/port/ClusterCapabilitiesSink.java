package io.aipaas.cluster.agent.observability.port;

/**
 * {@link ClusterCapabilities} 의 write-side counterpart — agent 가 보고한 cluster capability 정보를
 * 호스트 영구 저장소에 반영하는 SPI.
 *
 * <p>Agent heartbeat 에 piggy-back 된 {@code AgentHealth.gpu_node_count} 를 starter 측 listener 가
 * 읽어 본 sink 의 {@link #setHasGpuNodes} 를 호출. anycloud 는 ClusterEntity 의 has_gpu_nodes 컬럼을 update.
 *
 * <p>구현 시 유의:
 * <ul>
 *   <li>매 heartbeat (30 s) 마다 호출되므로 변경 감지 후에만 DB write — 불필요한 update 폭주 방지</li>
 *   <li>본 인터페이스를 호스트가 미제공하면 starter listener 는 자동 disable (Sink bean 없음 → listener
 *       자체가 bean 으로 등록되지 않음)</li>
 *   <li>예외 throw 금지 — listener 가 catch 해 swallow 하지만 stream 무관 보호 차원에서 silent best-effort
 *       권장</li>
 * </ul>
 */
public interface ClusterCapabilitiesSink {

	/**
	 * Cluster 의 GPU 노드 존재 여부 update.
	 *
	 * @param clusterName 대상 cluster
	 * @param value       agent 가 감지한 최신 값 — gpu_node_count > 0 면 true
	 */
	void setHasGpuNodes(String clusterName, boolean value);
}
