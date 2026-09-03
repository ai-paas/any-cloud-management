package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.core.AgentStatus;
import java.time.Instant;

/**
 * Cluster Agent 의 종합 health 정보. UI/REST 응답으로 그대로 직렬화 가능.
 *
 * <p>3 source 종합:
 * <ol>
 *   <li>persistence: {@link io.aipaas.cluster.agent.core.AgentIdentity#status} + lastSeenAt</li>
 *   <li>in-memory: {@link AgentSessionRegistry} 의 active stream 유무</li>
 *   <li>합성: heartbeat staleness threshold 안의 신호 → healthy</li>
 * </ol>
 *
 * @param clusterName        대상 cluster 식별자.
 * @param healthy            동시 만족: status==ACTIVE && streamActive && heartbeatFresh.
 * @param summary            사용자 노출용 메시지 (e.g. "stream up, heartbeat 12s ago").
 * @param agentStatus        DB 의 최신 status. "NONE" 이면 등록된 agent 없음.
 * @param streamActive       in-memory 세션 registry 에 active stream 존재 여부.
 * @param lastSeenAt         가장 최근 stream 활동 시각.
 * @param lastK8sApiOkAt     Agent 측 K8s API 정상 통신 마지막 시각.
 * @param lastSeenSecondsAgo lastSeenAt 과 now 의 초 단위 차이. null 이면 신호 없음.
 */
public record ClusterHealth(
		String clusterName,
		boolean healthy,
		String summary,
		String agentStatus,
		boolean streamActive,
		Instant lastSeenAt,
		Instant lastK8sApiOkAt,
		Long lastSeenSecondsAgo) {

	/** Agent 자체가 미등록된 cluster 에 대한 표준 응답. */
	public static ClusterHealth noAgent(String clusterName) {
		return new ClusterHealth(
				clusterName, false, "no agent registered yet",
				"NONE", false, null, null, null);
	}

	public boolean hasAgent() {
		return !"NONE".equals(agentStatus);
	}

	public AgentStatus agentStatusEnum() {
		try {
			return AgentStatus.valueOf(agentStatus);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
