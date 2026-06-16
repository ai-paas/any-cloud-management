package io.aipaas.cluster.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cluster Agent 신원 정보의 영구 저장소 SPI.
 *
 * <p>호스트 애플리케이션이 본 인터페이스를 구현해서 Spring bean 으로 등록하면 starter 가 자동 인식.
 * Anycloud 는 JPA(MariaDB), 다른 프로젝트는 MongoDB / DynamoDB / 기타 RDBMS 등 자유롭게 선택 가능.
 *
 * <p><b>구현 시 유의사항</b>:
 * <ul>
 *   <li>모든 메서드는 thread-safe 가정. 동시 호출 가능.</li>
 *   <li>{@link #findByIdentityTokenHash(String)} 는 hot path — 매 stream 인증 시 호출. index 권장.</li>
 *   <li>{@link #updateLastSeen} 는 heartbeat (30s 주기) 마다 호출 — best-effort, 실패해도 stream 유지.</li>
 *   <li>{@link #save(AgentIdentity)} 는 upsert 의미 (agentId 기준). 신규/업데이트 모두 처리.</li>
 * </ul>
 */
public interface AgentIdentityStore {

	/**
	 * Agent 가 metadata 로 보낸 Bearer token 의 SHA-256 hash 로 조회. Runtime stream 인증의 hot path.
	 *
	 * @return 매칭되는 identity. 토큰이 unknown 이면 {@code Optional.empty()}.
	 */
	Optional<AgentIdentity> findByIdentityTokenHash(String tokenHash);

	/**
	 * Cluster 의 모든 agent 신원 (HA 시나리오 대비 multi-row).
	 *
	 * <p>예: 같은 cluster 에 {@code instance-1} (REGISTERED), {@code instance-2} (ACTIVE) 두 행 가능.
	 */
	List<AgentIdentity> findByClusterName(String clusterName);

	/**
	 * 신규 또는 update. {@code agentId} 가 unique key. 같은 id 면 덮어쓰기.
	 *
	 * @return 저장된 최신 상태 (DB 가 timestamp/version 채워준 경우 반영).
	 */
	AgentIdentity save(AgentIdentity identity);

	/**
	 * 상태 + 에러 메시지 update. {@code agentId} 기준.
	 *
	 * @return update 성공 여부. {@code false} 면 unknown agentId.
	 */
	boolean updateStatus(String agentId, AgentStatus status, String errorMessage);

	/**
	 * Heartbeat 도착 시 lastSeenAt + lastK8sApiOkAt 업데이트. {@code clusterName} 기준 (HA 시 ACTIVE
	 * 인 첫 행 갱신 등 구현체 자유).
	 *
	 * @return update 된 행 수 (0 이면 ACTIVE 행 없음).
	 */
	int updateLastSeen(String clusterName, Instant lastSeenAt, Instant lastK8sApiOkAt);

	/**
	 * Identity token rotation. 같은 agentId 의 token hash + expires_at 을 교체.
	 *
	 * <p>caller 가 신규 token 의 hash + 새 expiresAt 을 계산 후 호출. atomic 으로 처리 (race —
	 * concurrent rotation 호출 방지) 권장이지만 구현체 자유.
	 *
	 * @return update 된 행 (변경된 hash + expires 반영). agentId 미존재 시 {@link
	 *     java.util.NoSuchElementException} 또는 null/empty Optional 등 구현체 선택. 본 SPI 는 null
	 *     반환을 unknown 으로 약속.
	 */
	AgentIdentity rotateToken(String agentId, String newIdentityTokenHash, Instant newExpiresAt);
}
