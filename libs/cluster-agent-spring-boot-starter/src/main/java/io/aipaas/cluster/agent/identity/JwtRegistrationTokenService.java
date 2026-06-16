package io.aipaas.cluster.agent.identity;

import io.aipaas.cluster.agent.core.IdempotencyStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent bootstrap 용 단기 1회용 JWT 발급/검증.
 * <ul>
 *   <li>Claim: iss/aud/sub/scope=agent:register/cluster_id/install_mode/jti/exp</li>
 *   <li>검증: 서명+만료 → aud/scope/iss 일치 → jti 1회 사용 ({@link IdempotencyStore#tryLock})</li>
 * </ul>
 * @see docs/architecture/cluster-agent.md
 */
@Slf4j
public class JwtRegistrationTokenService {

	public static final String SCOPE_AGENT_REGISTER = "agent:register";
	public static final String CLAIM_CLUSTER_ID = "cluster_id";
	public static final String CLAIM_INSTALL_MODE = "install_mode";
	public static final String CLAIM_SCOPE = "scope";

	private final AgentJwtProperties properties;
	private final IdempotencyStore idempotencyStore;
	private final Clock clock;
	private final SigningKeyResolver signingKeyResolver;

	private SecretKey signingKey;

	/**
	 * 영구 저장 키를 사용하는 production 권장 생성자. 호스트 application 이
	 * {@link SigningKeyResolver} 의 영구 저장 구현 (예: JpaSigningKeyResolver) 를 주입.
	 */
	public JwtRegistrationTokenService(AgentJwtProperties properties,
			IdempotencyStore idempotencyStore,
			Clock clock,
			SigningKeyResolver signingKeyResolver) {
		this.properties = properties;
		this.idempotencyStore = idempotencyStore;
		this.clock = clock;
		this.signingKeyResolver = signingKeyResolver;
	}

	/**
	 * Legacy 3-arg 생성자 — 기존 코드 / 테스트 호환. {@link PropertySigningKeyResolver} 로 wrap.
	 * 신규 코드는 4-arg 권장.
	 */
	public JwtRegistrationTokenService(AgentJwtProperties properties,
			IdempotencyStore idempotencyStore,
			Clock clock) {
		this(properties, idempotencyStore, clock, new PropertySigningKeyResolver(properties));
	}

	@PostConstruct
	public void initSigningKey() {
		byte[] keyBytes = signingKeyResolver.resolveSigningKey();
		if (keyBytes == null || keyBytes.length < 32) {
			throw new IllegalStateException(
					"SigningKeyResolver returned invalid key (must be >= 32 bytes). length="
							+ (keyBytes == null ? -1 : keyBytes.length));
		}
		signingKey = Keys.hmacShaKeyFor(keyBytes);
		log.info("Cluster Agent JWT signing key initialized (length={}b, alg=HS256, resolver={})",
				keyBytes.length, signingKeyResolver.getClass().getSimpleName());
	}

	/** registration_token 발급 — 단기 (~10분, ttl-seconds 설정) JWT. */
	public IssuedToken issue(String clusterId, String installMode) {
		Instant now = clock.instant();
		Instant exp = now.plusSeconds(properties.jwt().ttlSeconds());
		String jti = UUID.randomUUID().toString();

		String token = Jwts.builder()
				.issuer(properties.jwt().issuer())
				.audience().add(properties.jwt().audience()).and()
				.subject("cluster:" + clusterId)
				.claim(CLAIM_CLUSTER_ID, clusterId)
				.claim(CLAIM_INSTALL_MODE, installMode)
				.claim(CLAIM_SCOPE, SCOPE_AGENT_REGISTER)
				.id(jti)
				.issuedAt(Date.from(now))
				.expiration(Date.from(exp))
				.signWith(signingKey)
				.compact();

		log.debug("Issued registration_token cluster_id={} jti={} expires_at={}", clusterId, jti, exp);
		return new IssuedToken(token, jti, exp, properties.jwt().ttlSeconds());
	}

	/** registration_token 검증 + jti 1회 사용 lock. 실패 시 {@link RegistrationTokenInvalidException}. */
	public RegistrationClaims verifyAndConsume(String token) {
		Claims claims;
		try {
			claims = Jwts.parser()
					.verifyWith(signingKey)
					.requireAudience(properties.jwt().audience())
					.requireIssuer(properties.jwt().issuer())
					.build()
					.parseSignedClaims(token)
					.getPayload();
		} catch (JwtException e) {
			throw new RegistrationTokenInvalidException(
					"registration_token verification failed: " + e.getMessage(), e);
		}

		String scope = claims.get(CLAIM_SCOPE, String.class);
		if (!SCOPE_AGENT_REGISTER.equals(scope)) {
			throw new RegistrationTokenInvalidException(
					"scope mismatch: expected " + SCOPE_AGENT_REGISTER + ", got " + scope);
		}

		String clusterId = claims.get(CLAIM_CLUSTER_ID, String.class);
		String installMode = claims.get(CLAIM_INSTALL_MODE, String.class);
		String jti = claims.getId();

		if (clusterId == null || clusterId.isBlank() || jti == null) {
			throw new RegistrationTokenInvalidException("token missing required claims (cluster_id/jti)");
		}

		// JTI 1회 사용 강제 — replay 공격 방어.
		Duration lockTtl = Duration.ofSeconds(properties.jwt().ttlSeconds() + 60);
		if (!idempotencyStore.tryLock("bootstrap:jti:" + jti, lockTtl)) {
			throw new RegistrationTokenInvalidException("registration_token already used (jti=" + jti + ")");
		}

		Instant expiresAt = claims.getExpiration().toInstant();
		return new RegistrationClaims(clusterId, installMode, jti, expiresAt, Map.copyOf(claims));
	}

	/** 발급 결과 — token 자체는 1회만 사용 후 폐기. */
	public record IssuedToken(String token, String jti, Instant expiresAt, long ttlSeconds) {}

	/** verifyAndConsume 의 통과 후 보장된 claim 들. */
	public record RegistrationClaims(
			String clusterId,
			String installMode,
			String jti,
			Instant expiresAt,
			Map<String, Object> rawClaims) {}

	/** registration_token 검증 실패 — REST 는 400/401 매핑, gRPC 는 PERMISSION_DENIED 매핑. */
	public static class RegistrationTokenInvalidException extends RuntimeException {
		public RegistrationTokenInvalidException(String message) {
			super(message);
		}
		public RegistrationTokenInvalidException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
