package io.aipaas.cluster.agent.identity;

/**
 * Cluster Agent registration_token (JWT) 서명용 HMAC 키 resolver SPI.
 *
 * <p>기본 구현 ({@link PropertySigningKeyResolver}) 은 {@code cluster-agent.jwt.secret} config 에서
 * 키를 읽음. 호스트 application 이 영구 저장 (DB / Vault / K8s Secret) 으로 대체하려면 본 인터페이스를
 * {@code @Component} 로 구현해 starter 의 default bean 을 덮어쓴다.
 *
 * <p><b>중요</b>: 본 메서드는 startup 시 1회만 호출됨 ({@link JwtRegistrationTokenService#initSigningKey}).
 * 따라서 결과는 deterministic 해야 하며, 호출 간 동일한 키를 반환해야 함 — JVM 재시작 후에도 같은 키를
 * 반환하지 못하면 기존에 발급된 모든 registration_token JWT 의 signature 가 invalid 가 됨 (등록된
 * agent 가 token rotation 시도 시 PERMISSION_DENIED). 영구 저장이 핵심 요구사항.
 *
 * <p>contract:
 * <ul>
 *   <li>반환 키는 HS256 호환을 위해 32 bytes (256 bits) 이상이어야 함</li>
 *   <li>구현 중 예외 발생 시 {@link IllegalStateException} 으로 startup 차단 권장</li>
 * </ul>
 */
@FunctionalInterface
public interface SigningKeyResolver {

	/**
	 * 서명 키 bytes 반환. startup 시 1회만 호출.
	 *
	 * @return HMAC-SHA256 호환 키 (>= 32 bytes)
	 */
	byte[] resolveSigningKey();
}
