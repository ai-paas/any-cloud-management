package io.aipaas.cluster.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentStatus;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.AgentSession;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Cluster health 종합 응답 회귀 보호 — SPI 기반.
 */
class AgentHealthServiceTest {

	private static final Instant NOW = Instant.parse("2026-05-12T12:00:00Z");
	private static final Duration THRESHOLD = Duration.ofSeconds(90);

	private AgentIdentityStore store;
	private AgentSessionRegistry registry;
	private AgentHealthService svc;

	@BeforeEach
	void setUp() {
		store = Mockito.mock(AgentIdentityStore.class);
		registry = Mockito.mock(AgentSessionRegistry.class);
		svc = new AgentHealthService(THRESHOLD, store, registry, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private AgentSession fakeSession() {
		@SuppressWarnings("unchecked")
		StreamObserver<ControlMessage> obs = (StreamObserver<ControlMessage>) Mockito.mock(StreamObserver.class);
		return new AgentSession("c1", "instance-1", obs, NOW.toEpochMilli());
	}

	private AgentIdentity active(Instant lastSeen) {
		return new AgentIdentity(
				"agent-1", "c1", "instance-1", "hash",
				AgentStatus.ACTIVE, lastSeen, null, null, null, null);
	}

	@Test
	void getHealth_noAgent_returnsUnhealthyWithNoneStatus() {
		when(store.findByClusterName("c1")).thenReturn(List.of());
		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isFalse();
		assertThat(h.agentStatus()).isEqualTo("NONE");
		assertThat(h.streamActive()).isFalse();
		assertThat(h.summary()).contains("no agent");
	}

	@Test
	void getHealth_allConditionsMet_returnsHealthy() {
		when(store.findByClusterName("c1")).thenReturn(List.of(active(NOW.minusSeconds(10))));
		when(registry.find("c1")).thenReturn(Optional.of(fakeSession()));

		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isTrue();
		assertThat(h.agentStatus()).isEqualTo("ACTIVE");
		assertThat(h.streamActive()).isTrue();
		assertThat(h.lastSeenSecondsAgo()).isEqualTo(10L);
		assertThat(h.summary()).contains("stream up");
	}

	@Test
	void getHealth_activeButNoStream_returnsUnhealthy() {
		when(store.findByClusterName("c1")).thenReturn(List.of(active(NOW.minusSeconds(10))));
		when(registry.find("c1")).thenReturn(Optional.empty());

		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isFalse();
		assertThat(h.streamActive()).isFalse();
		assertThat(h.summary()).contains("no live stream");
	}

	@Test
	void getHealth_streamUpButHeartbeatStale_returnsUnhealthy() {
		when(store.findByClusterName("c1")).thenReturn(List.of(active(NOW.minusSeconds(120))));
		when(registry.find("c1")).thenReturn(Optional.of(fakeSession()));

		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isFalse();
		assertThat(h.summary()).contains("stale");
	}

	@Test
	void getHealth_failedStatus_returnsUnhealthy() {
		AgentIdentity failed = new AgentIdentity(
				"agent-1", "c1", "instance-1", "hash",
				AgentStatus.FAILED, NOW, null, null, null, null);
		when(store.findByClusterName("c1")).thenReturn(List.of(failed));

		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isFalse();
		assertThat(h.agentStatus()).isEqualTo("FAILED");
		assertThat(h.summary()).contains("not ACTIVE");
	}

	@Test
	void getHealth_multipleAgentRows_picksMostRecentLastSeen() {
		AgentIdentity oldOne = active(NOW.minusSeconds(600));
		AgentIdentity recent = active(NOW.minusSeconds(5));
		when(store.findByClusterName("c1")).thenReturn(List.of(oldOne, recent));
		when(registry.find("c1")).thenReturn(Optional.of(fakeSession()));

		ClusterHealth h = svc.getHealth("c1");
		assertThat(h.healthy()).isTrue();
		assertThat(h.lastSeenSecondsAgo()).isEqualTo(5L);
	}
}
