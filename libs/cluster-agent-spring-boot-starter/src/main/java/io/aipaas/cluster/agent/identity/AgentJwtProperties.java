package io.aipaas.cluster.agent.identity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * registration_token (단기 JWT) + agent_identity_token (장기 opaque) 설정.
 *
 * <p>application.yaml 의 {@code cluster-agent.jwt.*} / {@code cluster-agent.identity.*} 매핑.
 *
 * <p>호환성을 위해 anycloud 는 별도로 {@code agent.jwt.*} prefix 도 사용 가능 — 본 starter 는
 * {@code cluster-agent.*} 가 canonical, 다른 prefix 는 host application 에서 alias 로 처리.
 */
@Validated
@ConfigurationProperties(prefix = "cluster-agent")
public record AgentJwtProperties(Jwt jwt, Identity identity) {

	public AgentJwtProperties {
		if (jwt == null) {
			jwt = new Jwt(null, "cluster-agent", "cluster-agent-registration", 600);
		}
		if (identity == null) {
			identity = new Identity(365);
		}
	}

	public record Jwt(
			/** HS256 서명 키 (>= 32 bytes). 비어 있으면 startup 시 임의 생성 (dev 만). prod 는 반드시 env. */
			String secret,
			@NotBlank String issuer,
			@NotBlank String audience,
			@Min(60) long ttlSeconds) {
	}

	public record Identity(
			@Min(1) int ttlDays) {
	}
}
