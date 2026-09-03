package io.aipaas.cluster.agent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.aipaas.cluster.agent.v1.ExecPacket;
import io.aipaas.cluster.agent.v1.ExecStatus;
import io.aipaas.cluster.agent.terminal.ExecSessionRegistry.PendingExecSession;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ExecSessionRegistry 의 pending 매핑 / bind / timeout 회귀.
 */
class ExecSessionRegistryTest  {

	private ExecSessionRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new ExecSessionRegistry();
	}

	@AfterEach
	void tearDown() {
		registry.shutdown();
	}

	@Test
	void createPending_returnsUniqueSessionIds() {
		PendingExecSession a = registry.createPending(Duration.ofSeconds(10));
		PendingExecSession b = registry.createPending(Duration.ofSeconds(10));
		assertThat(a.sessionId()).isNotEqualTo(b.sessionId());
		assertThat(registry.pendingCount()).isEqualTo(2);
	}

	@Test
	void bindAgentStream_returnsBridgeForKnownSession() {
		PendingExecSession p = registry.createPending(Duration.ofSeconds(10));
		StreamObserver<ExecPacket> dummy = new NoopObserver();

		ExecBridge bound = registry.bindAgentStream(p.sessionId(), dummy);

		assertThat(bound).isNotNull();
		assertThat(bound.isAgentBound()).isTrue();
		assertThat(bound).isSameAs(p.bridge());
	}

	@Test
	void bindAgentStream_returnsNullForUnknownSession() {
		ExecBridge bound = registry.bindAgentStream("does-not-exist", new NoopObserver());
		assertThat(bound).isNull();
	}

	@Test
	void bindAgentStream_returnsNullForAlreadyBound() {
		PendingExecSession p = registry.createPending(Duration.ofSeconds(10));
		registry.bindAgentStream(p.sessionId(), new NoopObserver());
		ExecBridge second = registry.bindAgentStream(p.sessionId(), new NoopObserver());
		assertThat(second).isNull();     // 중복 bind 거부 — replay 방어.
	}

	@Test
	void pendingSession_expiresAndMarksFailed() {
		AtomicReference<ExecStatus> received = new AtomicReference<>();
		PendingExecSession p = registry.createPending(Duration.ofMillis(80));
		p.bridge().setCallbacks(new ExecBridge.Callbacks() {
			@Override public void onStdout(byte[] data) {}
			@Override public void onStderr(byte[] data) {}
			@Override public void onEnd(ExecStatus status) { received.set(status); }
		});

		// Wait for expiration.
		await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
			assertThat(received.get()).isNotNull();
		});
		assertThat(received.get().getErrorCode()).isEqualTo("AGENT_UNAVAILABLE");
		assertThat(registry.pendingCount()).isZero();
	}

	@Test
	void remove_clearsPending() {
		PendingExecSession p = registry.createPending(Duration.ofSeconds(10));
		assertThat(registry.pendingCount()).isEqualTo(1);
		registry.remove(p.sessionId());
		assertThat(registry.pendingCount()).isZero();
	}

	private static class NoopObserver implements StreamObserver<ExecPacket> {
		@Override public void onNext(ExecPacket value) {}
		@Override public void onError(Throwable t) {}
		@Override public void onCompleted() {}
	}
}
