package io.aipaas.cluster.agent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.core.IdempotencyStore;
import io.aipaas.cluster.agent.identity.AgentJwtProperties.Identity;
import io.aipaas.cluster.agent.identity.AgentJwtProperties.Jwt;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.RegistrationClaims;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.RegistrationTokenInvalidException;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * registration_token JWT 발급/검증/JTI 1회 강제 회귀 보호 — IdempotencyStore SPI 사용.
 */
class JwtRegistrationTokenServiceTest {

	private static final String SECRET = "test-secret-32-bytes-min-length-padding-padding-padding-padding";
	private static final String ISSUER = "anycloud-bootstrap";
	private static final String AUDIENCE = "cluster-agent-registration";

	private IdempotencyStore idempotencyStore;
	private JwtRegistrationTokenService service;

	@BeforeEach
	void setUp() {
		idempotencyStore = Mockito.mock(IdempotencyStore.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		when(idempotencyStore.tryLock(anyString(), any(Duration.class))).thenReturn(true);

		AgentJwtProperties props = new AgentJwtProperties(
				new Jwt(SECRET, ISSUER, AUDIENCE, 600),
				new Identity(60));
		service = new JwtRegistrationTokenService(props, idempotencyStore, Clock.systemUTC());
		service.initSigningKey();
	}

	@Test
	void issue_returnsValidJwt_withRequiredClaims() {
		IssuedToken issued = service.issue("demo-aws-01", "MANUAL");
		assertThat(issued.token()).isNotBlank();
		assertThat(issued.jti()).isNotBlank();
		assertThat(issued.ttlSeconds()).isEqualTo(600);
		assertThat(issued.token().split("\\.")).hasSize(3);
	}

	@Test
	void verifyAndConsume_validToken_returnsClaims() {
		IssuedToken issued = service.issue("demo-aws-01", "HELM_BOOTSTRAP");
		RegistrationClaims claims = service.verifyAndConsume(issued.token());

		assertThat(claims.clusterId()).isEqualTo("demo-aws-01");
		assertThat(claims.installMode()).isEqualTo("HELM_BOOTSTRAP");
		assertThat(claims.jti()).isEqualTo(issued.jti());
		verify(idempotencyStore).tryLock(eq("bootstrap:jti:" + issued.jti()), any(Duration.class));
	}

	@Test
	void verifyAndConsume_jtiAlreadyUsed_throws() {
		IssuedToken issued = service.issue("demo-aws-01", "MANUAL");
		when(idempotencyStore.tryLock(anyString(), any(Duration.class))).thenReturn(false);

		assertThatThrownBy(() -> service.verifyAndConsume(issued.token()))
				.isInstanceOf(RegistrationTokenInvalidException.class)
				.hasMessageContaining("already used");
	}

	@Test
	void verifyAndConsume_tamperedToken_throws() {
		IssuedToken issued = service.issue("demo-aws-01", "MANUAL");
		String tampered = issued.token().substring(0, issued.token().length() - 5) + "XXXXX";

		assertThatThrownBy(() -> service.verifyAndConsume(tampered))
				.isInstanceOf(RegistrationTokenInvalidException.class)
				.hasMessageContaining("verification failed");
	}

	@Test
	void verifyAndConsume_wrongAudience_throws() {
		AgentJwtProperties otherProps = new AgentJwtProperties(
				new Jwt(SECRET, ISSUER, "different-audience", 600),
				new Identity(60));
		JwtRegistrationTokenService otherService =
				new JwtRegistrationTokenService(otherProps, idempotencyStore, Clock.systemUTC());
		otherService.initSigningKey();
		IssuedToken issued = otherService.issue("demo-aws-01", "MANUAL");

		assertThatThrownBy(() -> service.verifyAndConsume(issued.token()))
				.isInstanceOf(RegistrationTokenInvalidException.class);
	}

	@Test
	void verifyAndConsume_calledExactlyOncePerJti() {
		IssuedToken issued = service.issue("demo-aws-01", "MANUAL");
		service.verifyAndConsume(issued.token());

		verify(idempotencyStore, times(1)).tryLock(anyString(), any(Duration.class));
	}

	@Test
	void issue_emptySecret_producesEphemeralButFunctionalToken() {
		AgentJwtProperties devProps = new AgentJwtProperties(
				new Jwt("", ISSUER, AUDIENCE, 600), new Identity(60));
		JwtRegistrationTokenService devService =
				new JwtRegistrationTokenService(devProps, idempotencyStore, Clock.systemUTC());
		devService.initSigningKey();

		IssuedToken issued = devService.issue("demo-aws-01", "MANUAL");
		assertThat(issued.token()).isNotBlank();
		RegistrationClaims claims = devService.verifyAndConsume(issued.token());
		assertThat(claims.clusterId()).isEqualTo("demo-aws-01");
	}

	@Test
	void issue_secretTooShort_throwsAtInit() {
		AgentJwtProperties shortProps = new AgentJwtProperties(
				new Jwt("too-short", ISSUER, AUDIENCE, 600), new Identity(60));
		JwtRegistrationTokenService shortService =
				new JwtRegistrationTokenService(shortProps, idempotencyStore, Clock.systemUTC());

		assertThatThrownBy(shortService::initSigningKey)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must be >= 32 bytes");
	}
}
