package io.aipaas.cluster.agent.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link SigningKeyResolver} — {@code cluster-agent.jwt.secret} config 값을 사용.
 *
 * <p>비어 있으면 startup 마다 random ephemeral 키 생성 + log.warn. <b>DEV ONLY</b> — JVM 재시작 시
 * 기존 JWT 가 invalid 가 되어 등록된 agent 의 재시작이 막힘. 운영에선 host application 이
 * {@code JpaSigningKeyResolver} 등 영구 저장 구현으로 본 default 를 덮어쓰는 것이 필수.
 */
@Slf4j
@RequiredArgsConstructor
public class PropertySigningKeyResolver implements SigningKeyResolver {

	private final AgentJwtProperties properties;

	@Override
	public byte[] resolveSigningKey() {
		String secret = properties.jwt().secret();
		if (secret == null || secret.isBlank()) {
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			log.warn("cluster-agent.jwt.secret not set + no persistent SigningKeyResolver bean — "
					+ "using ephemeral random key (DEV ONLY). Tokens invalidated on JVM restart. "
					+ "운영에선 영구 저장 SigningKeyResolver bean 등록 필요.");
			return random;
		}
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException(
					"cluster-agent.jwt.secret must be >= 32 bytes (256 bits) for HS256. Current: "
							+ keyBytes.length);
		}
		return keyBytes;
	}
}
