package io.aipaas.cluster.agent.core;

import java.time.Instant;

/**
 * 영구 저장소에 보관되는 Cluster Agent 의 신원 정보.
 *
 * <p>Starter 가 노출하는 표준 도메인 모델. 호스트 애플리케이션의 ORM 엔티티/도큐먼트와 별도로 본 record
 * 인스턴스만 SPI 경계를 가로지른다. {@link AgentIdentityStore} 구현체가 ORM 엔티티 ↔ 본 record 변환을
 * 담당.
 *
 * <p>모든 필드는 immutable. update 가 필요하면 {@link AgentIdentityStore} 의 mutation 메서드를 사용.
 *
 * <p>mTLS 제거. cert 관련 필드 (certSerial, certExpiresAt, previousCert*) 폐기. Bearer
 * (identityTokenHash) 단일 인증. Rancher 와 동일한 모델로 단순화.
 *
 * @param agentId            Backend 가 발급한 unique id (예: UUID). 같은 cluster 라도 instance 별 별도.
 * @param clusterName        대상 cluster 식별자 (host 가 정의한 이름, 예: "demo-aws-01").
 * @param agentInstanceId    HA 시나리오에서 같은 cluster 의 여러 agent pod 구분. Pod UID 권장.
 * @param identityTokenHash  SHA-256 hex hash. 평문 token 은 저장 X — agent 에게 1회만 발급 후 폐기.
 * @param status             현재 lifecycle 상태.
 * @param lastSeenAt         마지막 stream 활동 시각 (heartbeat 포함). null 가능 (등록 직후).
 * @param lastK8sApiOkAt     Agent 측에서 K8s API 정상 통신 마지막 시각. null 가능.
 * @param expiresAt          Identity token 만료 시각. null 이면 무기한 (보통 60일 등 길게 발급).
 * @param revokedAt          운영자가 revoke 한 시각. null 이면 유효.
 * @param lastError          최근 실패 메시지 (FAILED 상태일 때 진단용). null 가능.
 */
public record AgentIdentity(
		String agentId,
		String clusterName,
		String agentInstanceId,
		String identityTokenHash,
		AgentStatus status,
		Instant lastSeenAt,
		Instant lastK8sApiOkAt,
		Instant expiresAt,
		Instant revokedAt,
		String lastError) {

	/**
	 * Stream 인증에 사용 가능한 유효 신원인지 확인.
	 * <ul>
	 *   <li>revoke 되지 않았고</li>
	 *   <li>아직 만료 안 됐고</li>
	 *   <li>status 가 FAILED / REVOKED 아닌 경우</li>
	 * </ul>
	 */
	public boolean isAuthValid(Instant now) {
		if (revokedAt != null) {
			return false;
		}
		if (expiresAt != null && expiresAt.isBefore(now)) {
			return false;
		}
		return status != AgentStatus.FAILED && status != AgentStatus.REVOKED;
	}

	/**
	 * 새 상태로 교체한 복사본 생성. {@link AgentIdentityStore} 구현체에서 update-by-id 처리할 때 사용.
	 */
	public AgentIdentity withStatus(AgentStatus newStatus, String errorMessage) {
		return new AgentIdentity(
				agentId, clusterName, agentInstanceId, identityTokenHash,
				newStatus, lastSeenAt, lastK8sApiOkAt, expiresAt, revokedAt, errorMessage);
	}

	public AgentIdentity withLastSeen(Instant lastSeen, Instant lastK8sApiOk) {
		return new AgentIdentity(
				agentId, clusterName, agentInstanceId, identityTokenHash,
				status, lastSeen, lastK8sApiOk, expiresAt, revokedAt, lastError);
	}
}
