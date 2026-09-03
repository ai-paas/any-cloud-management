package io.aipaas.cluster.agent.identity;

import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runtime stream 인증 — Bearer agent_identity_token → AgentIdentity lookup + 유효성 검증.
 *
 * <p>Cluster Agent runtime gRPC endpoint 가 stream open 시 한 번 호출. 통과 시 {@link AgentIdentity}
 * 반환 — 세션 레지스트리에 등록할 cluster_name 과 agent_instance_id 를 얻음.
 *
 * <p>검증 항목 (모두 {@link AgentIdentity#isAuthValid}):
 * <ol>
 *   <li>Token hash (SHA-256) 가 store 에 존재 ({@link AgentIdentityStore#findByIdentityTokenHash})</li>
 *   <li>revoked_at == null (회수 안 됨)</li>
 *   <li>expires_at &gt; now (만료 안 됨)</li>
 *   <li>status != FAILED / REVOKED</li>
 * </ol>
 *
 * <p>실패 사유는 log 로만 남기고 호출자에게는 빈 Optional 반환 — 외부에 구체 사유 노출 시 brute-force
 * 추측 정보 leak 우려.
 *
 * <p>mTLS 제거. cert subject / cert-only auth path 모두 폐기. Bearer 단일 path.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentIdentityAuthenticator {

	private final AgentIdentityStore identityStore;
	private final Clock clock;

	/**
	 * Bearer identity_token → AgentIdentity. 실패 시 빈 Optional + log 만 (정보 leak 방지).
	 *
	 * @return 통과 시 유효한 {@link AgentIdentity}. 실패 시 빈 Optional + 자세한 reason 은 log.
	 */
	public Optional<AgentIdentity> authenticate(String identityToken) {
		if (identityToken == null || identityToken.isBlank()) {
			log.debug("Runtime auth rejected: empty token");
			return Optional.empty();
		}
		String hash = TokenHasher.sha256Hex(identityToken);
		Optional<AgentIdentity> found = identityStore.findByIdentityTokenHash(hash);
		if (found.isEmpty()) {
			log.debug("Runtime auth rejected: token hash not found");
			return Optional.empty();
		}
		AgentIdentity agent = found.get();
		if (!agent.isAuthValid(clock.instant())) {
			log.warn("Runtime auth rejected: identity not valid cluster={} status={} revoked={} expires={}",
					agent.clusterName(), agent.status(), agent.revokedAt(), agent.expiresAt());
			return Optional.empty();
		}
		return Optional.of(agent);
	}
}
