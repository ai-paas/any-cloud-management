package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Cluster Agent 의 종합 health 정보를 계산.
 *
 * <p>여러 source 종합:
 * <ol>
 *   <li>SPI: {@link AgentIdentityStore#findByClusterName} 으로 DB 상태 조회</li>
 *   <li>In-memory: {@link AgentSessionRegistry#find} 가 active stream 가졌는지</li>
 *   <li>합성: heartbeat 가 threshold 안이면 fresh</li>
 * </ol>
 *
 * <p>"Healthy" 정의: 동시 만족
 * <ol>
 *   <li>persisted status == ACTIVE</li>
 *   <li>stream registry 에 active session 존재</li>
 *   <li>last_seen_at 가 threshold 이내</li>
 * </ol>
 */
@Slf4j
public class AgentHealthService {

	/**
	 * Heartbeat 가 이 기간보다 오래되면 unhealthy (default 30s heartbeat 기준 3배).
	 *
	 * <p>{@code volatile} — runtime 에 {@link #setHeartbeatStalenessThreshold} 로 변경 가능.
	 * 운영자가 admin endpoint 로 false-positive 임계치 튜닝 시 재시작 없이 즉시 적용.
	 */
	private volatile Duration heartbeatStalenessThreshold;

	private final AgentIdentityStore identityStore;
	private final AgentSessionRegistry sessionRegistry;
	private final Clock clock;

	public AgentHealthService(Duration heartbeatStalenessThreshold,
			AgentIdentityStore identityStore,
			AgentSessionRegistry sessionRegistry,
			Clock clock) {
		this.heartbeatStalenessThreshold = Objects.requireNonNull(heartbeatStalenessThreshold,
				"heartbeatStalenessThreshold");
		this.identityStore = identityStore;
		this.sessionRegistry = sessionRegistry;
		this.clock = clock;
	}

	/** 현재 적용 중인 threshold. */
	public Duration getHeartbeatStalenessThreshold() {
		return heartbeatStalenessThreshold;
	}

	/**
	 * Runtime tunable — 기본 90s 가 false-positive 발생 시 운영자가 즉시 늘려 잡을 수 있음.
	 * {@link Duration#isZero()} / {@link Duration#isNegative()} 는 거부 (모든 agent unhealthy 가 됨).
	 *
	 * @throws IllegalArgumentException null 또는 zero/negative
	 */
	public void setHeartbeatStalenessThreshold(Duration next) {
		if (next == null || next.isZero() || next.isNegative()) {
			throw new IllegalArgumentException(
					"heartbeatStalenessThreshold must be positive: " + next);
		}
		Duration previous = this.heartbeatStalenessThreshold;
		this.heartbeatStalenessThreshold = next;
		log.warn("AgentHealthService heartbeatStalenessThreshold: {} → {} (in-memory, instance-local)",
				previous, next);
	}

	public ClusterHealth getHealth(String clusterName) {
		List<AgentIdentity> agents = identityStore.findByClusterName(clusterName);
		if (agents.isEmpty()) {
			return ClusterHealth.noAgent(clusterName);
		}

		// HA: 같은 cluster 여러 agent instance 가능 — last_seen_at 가장 최신을 primary 로 선택.
		AgentIdentity primary = agents.stream()
				.max(Comparator.comparing(AgentIdentity::lastSeenAt,
						Comparator.nullsFirst(Comparator.naturalOrder())))
				.orElse(agents.get(0));

		boolean streamActive = sessionRegistry.find(clusterName).isPresent();
		Instant now = clock.instant();
		Long secondsAgo = primary.lastSeenAt() == null
				? null
				: Duration.between(primary.lastSeenAt(), now).getSeconds();
		boolean heartbeatFresh = secondsAgo != null
				&& secondsAgo <= heartbeatStalenessThreshold.getSeconds();

		boolean healthy = primary.status() == AgentStatus.ACTIVE
				&& streamActive
				&& heartbeatFresh;

		String summary = buildSummary(primary, streamActive, heartbeatFresh, secondsAgo);
		return new ClusterHealth(
				clusterName,
				healthy,
				summary,
				primary.status() == null ? "UNKNOWN" : primary.status().name(),
				streamActive,
				primary.lastSeenAt(),
				primary.lastK8sApiOkAt(),
				secondsAgo);
	}

	private String buildSummary(AgentIdentity agent, boolean streamActive, boolean heartbeatFresh,
			Long secondsAgo) {
		if (agent.status() != AgentStatus.ACTIVE) {
			return "agent status=" + agent.status() + " (not ACTIVE)";
		}
		if (!streamActive) {
			return "agent ACTIVE in store but no live stream — likely backend restart or network issue";
		}
		if (!heartbeatFresh) {
			return "heartbeat stale (" + secondsAgo + "s ago, threshold "
					+ heartbeatStalenessThreshold.getSeconds() + "s)";
		}
		return "stream up, heartbeat " + secondsAgo + "s ago";
	}
}
