package io.aipaas.cluster.agent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * agent_identity_token 검증 시나리오 — 정상 / 미발견 / revoked / expired / FAILED / blank.
 */
class AgentIdentityAuthenticatorTest {

	private static final String TOKEN = "abcdef1234567890";
	private static final String HASH = TokenHasher.sha256Hex(TOKEN);
	private static final Instant NOW = Instant.parse("2026-05-12T12:00:00Z");

	private AgentIdentityStore store;
	private AgentIdentityAuthenticator auth;

	@BeforeEach
	void setUp() {
		store = Mockito.mock(AgentIdentityStore.class);
		auth = new AgentIdentityAuthenticator(store, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private AgentIdentity active(AgentStatus status) {
		return new AgentIdentity(
				"agent-1", "demo-aws-01", "instance-1", HASH,
				status, null, null, NOW.plusSeconds(86_400), null, null);
	}

	@Test
	void authenticate_validToken_returnsAgent() {
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(active(AgentStatus.ACTIVE)));
		Optional<AgentIdentity> result = auth.authenticate(TOKEN);
		assertThat(result).isPresent();
		assertThat(result.get().clusterName()).isEqualTo("demo-aws-01");
	}

	@Test
	void authenticate_unknownTokenHash_rejects() {
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.empty());
		assertThat(auth.authenticate(TOKEN)).isEmpty();
	}

	@Test
	void authenticate_revokedAgent_rejects() {
		AgentIdentity revoked = new AgentIdentity(
				"agent-1", "demo-aws-01", "instance-1", HASH,
				AgentStatus.ACTIVE, null, null, NOW.plusSeconds(86_400),
				NOW.minusSeconds(60), null);
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(revoked));
		assertThat(auth.authenticate(TOKEN)).isEmpty();
	}

	@Test
	void authenticate_expiredToken_rejects() {
		AgentIdentity expired = new AgentIdentity(
				"agent-1", "demo-aws-01", "instance-1", HASH,
				AgentStatus.ACTIVE, null, null, NOW.minusSeconds(1), null, null);
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(expired));
		assertThat(auth.authenticate(TOKEN)).isEmpty();
	}

	@Test
	void authenticate_failedStatus_rejects() {
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(active(AgentStatus.FAILED)));
		assertThat(auth.authenticate(TOKEN)).isEmpty();
	}

	@Test
	void authenticate_revokedStatus_rejects() {
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(active(AgentStatus.REVOKED)));
		assertThat(auth.authenticate(TOKEN)).isEmpty();
	}

	@Test
	void authenticate_blankToken_rejects() {
		assertThat(auth.authenticate(null)).isEmpty();
		assertThat(auth.authenticate("")).isEmpty();
		assertThat(auth.authenticate("   ")).isEmpty();
	}

	@Test
	void authenticate_registeredStatus_accepts() {
		when(store.findByIdentityTokenHash(HASH)).thenReturn(Optional.of(active(AgentStatus.REGISTERED)));
		assertThat(auth.authenticate(TOKEN)).isPresent();
	}
}
