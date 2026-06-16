package io.aipaas.cluster.agent.support;

import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory {@link AgentIdentityStore} — Spring Boot starter 의 zero-config default.
 *
 * <p><b>용도</b>: dev / PoC / single-instance demo. K8s Secret 없이 starter 만 가져다
 * 빠르게 시도해볼 때.
 *
 * <p><b>제한</b>:
 * <ul>
 *   <li>Replication 불가 — 단일 JVM instance only.</li>
 *   <li>Restart 시 데이터 소실 — agent 가 모두 re-register 필요 (registration_token JWT 보유 가정).</li>
 *   <li>Concurrent 동시성은 보장 — ConcurrentHashMap.</li>
 * </ul>
 *
 * <p><b>Production 권장</b>: DB-backed 구현 (JPA / MongoDB / DynamoDB / etc.). 호스트가 자체
 * {@link AgentIdentityStore} bean 을 등록하면 본 default 는 자동 비활성 (@ConditionalOnMissingBean).
 *
 * <p><b>외부 재사용 시나리오</b>: starter 만 dependency 로 가져온 직후, JWT secret 만 설정하면
 * 본 구현이 자동 활성화되어 즉시 agent 가 등록 + 인증 가능. DB 셋업 부담 없이 "Hello World" 가능.
 */
@Slf4j
public class InMemoryAgentIdentityStore implements AgentIdentityStore {

	/** identity_token_hash → AgentIdentity. hot path lookup. */
	private final ConcurrentMap<String, AgentIdentity> byTokenHash = new ConcurrentHashMap<>();

	/** agentId → AgentIdentity. status / lastSeen update 용. */
	private final ConcurrentMap<String, AgentIdentity> byAgentId = new ConcurrentHashMap<>();

	public InMemoryAgentIdentityStore() {
		log.info("InMemoryAgentIdentityStore active — dev/PoC default. "
				+ "Register your own AgentIdentityStore @Bean for production (DB-backed).");
	}

	@Override
	public Optional<AgentIdentity> findByIdentityTokenHash(String tokenHash) {
		if (tokenHash == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(byTokenHash.get(tokenHash));
	}

	@Override
	public List<AgentIdentity> findByClusterName(String clusterName) {
		if (clusterName == null) {
			return List.of();
		}
		List<AgentIdentity> out = new ArrayList<>();
		for (AgentIdentity v : byAgentId.values()) {
			if (clusterName.equals(v.clusterName())) {
				out.add(v);
			}
		}
		return out;
	}

	@Override
	public AgentIdentity save(AgentIdentity identity) {
		if (identity == null || identity.agentId() == null) {
			throw new IllegalArgumentException("AgentIdentity.agentId must not be null");
		}
		// 같은 agent_id 의 이전 entry 의 token hash 삭제 — rotation / re-register 시 stale lookup 방지.
		AgentIdentity previous = byAgentId.get(identity.agentId());
		if (previous != null && previous.identityTokenHash() != null
				&& !previous.identityTokenHash().equals(identity.identityTokenHash())) {
			byTokenHash.remove(previous.identityTokenHash());
		}
		byAgentId.put(identity.agentId(), identity);
		if (identity.identityTokenHash() != null) {
			byTokenHash.put(identity.identityTokenHash(), identity);
		}
		return identity;
	}

	@Override
	public boolean updateStatus(String agentId, AgentStatus status, String errorMessage) {
		AgentIdentity cur = byAgentId.get(agentId);
		if (cur == null) {
			return false;
		}
		AgentIdentity updated = cur.withStatus(status, errorMessage);
		byAgentId.put(agentId, updated);
		if (updated.identityTokenHash() != null) {
			byTokenHash.put(updated.identityTokenHash(), updated);
		}
		return true;
	}

	@Override
	public AgentIdentity rotateToken(String agentId, String newIdentityTokenHash, Instant newExpiresAt) {
		AgentIdentity cur = byAgentId.get(agentId);
		if (cur == null) {
			return null;
		}
		// 이전 token hash 삭제 후 새 hash 로 매핑.
		if (cur.identityTokenHash() != null) {
			byTokenHash.remove(cur.identityTokenHash());
		}
		AgentIdentity rotated = new AgentIdentity(
				cur.agentId(), cur.clusterName(), cur.agentInstanceId(),
				newIdentityTokenHash,
				cur.status(), cur.lastSeenAt(), cur.lastK8sApiOkAt(),
				newExpiresAt,
				null,  // revoke 풀기 — rotation 으로 다시 살아남.
				cur.lastError());
		byAgentId.put(agentId, rotated);
		byTokenHash.put(newIdentityTokenHash, rotated);
		return rotated;
	}

	@Override
	public int updateLastSeen(String clusterName, Instant lastSeenAt, Instant lastK8sApiOkAt) {
		int updated = 0;
		for (AgentIdentity v : byAgentId.values()) {
			if (!clusterName.equals(v.clusterName())) {
				continue;
			}
			if (v.status() != AgentStatus.ACTIVE) {
				continue;
			}
			AgentIdentity next = v.withLastSeen(
					lastSeenAt != null ? lastSeenAt : v.lastSeenAt(),
					lastK8sApiOkAt != null ? lastK8sApiOkAt : v.lastK8sApiOkAt());
			byAgentId.put(v.agentId(), next);
			if (next.identityTokenHash() != null) {
				byTokenHash.put(next.identityTokenHash(), next);
			}
			updated++;
		}
		return updated;
	}
}
