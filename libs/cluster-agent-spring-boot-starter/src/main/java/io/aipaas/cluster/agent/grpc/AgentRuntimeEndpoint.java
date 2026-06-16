package io.aipaas.cluster.agent.grpc;

import io.aipaas.cluster.agent.v1.AgentMessage;
import io.aipaas.cluster.agent.v1.AgentRuntimeGrpc;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.ExecPacket;
import io.aipaas.cluster.agent.v1.Heartbeat;
import io.aipaas.cluster.agent.v1.LogPacket;
import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import io.aipaas.cluster.agent.core.AgentStatus;
import io.aipaas.cluster.agent.identity.AgentIdentityAuthenticator;
import io.aipaas.cluster.agent.logstream.LogStreamBridge;
import io.aipaas.cluster.agent.logstream.LogStreamSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.AgentSession;
import io.aipaas.cluster.agent.terminal.ExecBridge;
import io.aipaas.cluster.agent.terminal.ExecSessionRegistry;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.MDC;

/**
 * AgentRuntime 서비스 gRPC 핸들러 — agent ↔ backend bidi stream.
 *
 * <p><b>인증</b>: {@link AuthMetadataInterceptor} 가 metadata 의 Bearer agent_identity_token 을
 * io.grpc.Context 로 전달. 본 handler 가 첫 동작에서 {@link AgentIdentityAuthenticator} 로 검증.
 *
 * <p><b>3 개 RPC</b>:
 * <ul>
 *   <li>{@link #stream} — long-lived 명령/heartbeat/event bidi</li>
 *   <li>{@link #podExec} — 신규 PodExec bidi (별도 stream, session_id 로 WebSocket bridge 매칭)</li>
 * </ul>
 *
 * <p><b>흐름 (stream)</b>:
 * <pre>
 *   1. stream() invoke → token 검증 → AgentSession register
 *   2. ACTIVE 전환 + Lifecycle listener.onStreamConnected 호출
 *   3. Agent → Backend AgentMessage 수신:
 *        · response (request_id ≠ "") → SessionRegistry.completeResponse 로 pending future 완료
 *        · heartbeat → identityStore.updateLastSeen + listener.onHeartbeat
 *        · event → listener.onAgentEvent
 *   4. Backend → Agent ControlMessage 송신 은 SessionRegistry.sendCommand 가 onNext 호출
 *   5. onCompleted / onError → unregister + listener.onStreamDisconnected
 * </pre>
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class AgentRuntimeEndpoint extends AgentRuntimeGrpc.AgentRuntimeImplBase {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AgentIdentityAuthenticator authenticator;
	private final AgentSessionRegistry sessionRegistry;
	private final AgentIdentityStore identityStore;
	private final ExecSessionRegistry execSessionRegistry;
	private final LogStreamSessionRegistry logStreamSessionRegistry;
	private final List<AgentLifecycleListener> listeners;
	private final Clock clock;
	/** mtls.5 — auth path 사용 비율 모니터링용. nullable — Micrometer 없는 환경 호환. */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private MeterRegistry meterRegistry;

	/**
	 * {@code cluster_agent_auth_total{path=bearer|bearer+cert|cert-only, outcome=success|reject}}.
	 *
	 * <p>Metric prefix 가 host 이름(anycloud) 에 묶이지 않게 starter 정체성 (cluster_agent.*) 으로 정렬.
	 * external consumer 가 starter 사용 시 metric namespace 가 깔끔.
	 */
	private void recordAuthMetric(String path, String outcome) {
		if (meterRegistry == null || path == null) {
			return;
		}
		Counter.builder("cluster_agent.auth.total")
				.description("Runtime stream/exec/log auth events by path + outcome")
				.tags(Tags.of("path", path, "outcome", outcome))
				.register(meterRegistry)
				.increment();
	}

	/**
	 * mTLS Phase mtls.4 — {@code true} 면 bearer token 무시하고 cert subject + serial 만으로 인증.
	 * 기본 false (mtls.2/3 path 유지). 모든 cluster 가 mTLS 전환 완료된 시점에 운영자가 enable.
	 */
	@org.springframework.beans.factory.annotation.Value("${cluster-agent.mtls.cert-only-auth-enabled:false}")
	private boolean certOnlyAuth;

	/**
	 * 인증 결과 — agent identity + 어떤 경로 (bearer / cert) 로 인증됐는지. 로그 / metric 분기용.
	 */
	private record AuthOutcome(Optional<AgentIdentity> identity, String path, String failReason) {}

	/**
	 * 모든 stream/exec/log 엔트리가 공유하는 인증 로직.
	 *
	 * <p>mTLS 제거. cert-only / bearer+cert path 폐기. Bearer 단일 path.
	 */
	private AuthOutcome authenticateRequest() {
		AuthOutcome outcome = authenticateRequestInternal();
		recordAuthMetric(outcome.path(),
				outcome.identity().isPresent() ? "success" : "reject");
		return outcome;
	}

	private AuthOutcome authenticateRequestInternal() {
		String token = extractBearerToken();
		if (token == null) {
			return new AuthOutcome(Optional.empty(), "bearer",
					"Authorization header missing");
		}
		Optional<AgentIdentity> id = authenticator.authenticate(token);
		return new AuthOutcome(id, "bearer",
				id.isEmpty() ? "identity_token invalid / revoked / expired" : null);
	}

	@Override
	public StreamObserver<AgentMessage> stream(StreamObserver<ControlMessage> downstream) {
		AuthOutcome outcome = authenticateRequest();
		if (outcome.identity().isEmpty()) {
			log.warn("Runtime stream auth rejected path={} reason={}",
					outcome.path(), outcome.failReason());
			downstream.onError(Status.UNAUTHENTICATED
					.withDescription(outcome.failReason()).asRuntimeException());
			return new NoopAgentObserver();
		}
		AgentIdentity agent = outcome.identity().get();
		AgentSession session = sessionRegistry.register(
				agent.clusterName(), agent.agentInstanceId(), downstream);

		// Status 가 ACTIVE 가 아니면 ACTIVE 로 전환 + listener 알림.
		boolean transitionedToActive = agent.status() != AgentStatus.ACTIVE;
		if (transitionedToActive) {
			try {
				identityStore.updateStatus(agent.agentId(), AgentStatus.ACTIVE, null);
			} catch (Exception e) {
				log.warn("Failed to mark agent ACTIVE cluster={}: {}",
						agent.clusterName(), e.toString());
			}
		}
		AgentIdentity active = agent.withStatus(AgentStatus.ACTIVE, null);
		notifyListeners(l -> l.onStreamConnected(active));

		log.info("Runtime stream opened cluster={} instance={}",
				session.clusterId(), session.agentInstanceId());

		return new AgentObserver(session);
	}

	@Override
	public StreamObserver<ExecPacket> podExec(StreamObserver<ExecPacket> toAgent) {
		AuthOutcome outcome = authenticateRequest();
		if (outcome.identity().isEmpty()) {
			log.warn("podExec auth rejected path={} reason={}", outcome.path(), outcome.failReason());
			toAgent.onError(Status.UNAUTHENTICATED
					.withDescription(outcome.failReason()).asRuntimeException());
			return new NoopExecObserver();
		}
		return new ExecObserver(toAgent, outcome.identity().get().clusterName());
	}

	@Override
	public StreamObserver<LogPacket> streamPodLogs(StreamObserver<LogPacket> toAgent) {
		AuthOutcome outcome = authenticateRequest();
		if (outcome.identity().isEmpty()) {
			log.warn("streamPodLogs auth rejected path={} reason={}",
					outcome.path(), outcome.failReason());
			toAgent.onError(Status.UNAUTHENTICATED
					.withDescription(outcome.failReason()).asRuntimeException());
			return new NoopLogStreamObserver();
		}
		return new LogStreamObserver(toAgent, outcome.identity().get().clusterName());
	}

	private String extractBearerToken() {
		String auth = AuthMetadataInterceptor.AUTHORIZATION_CONTEXT.get();
		if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
			return null;
		}
		return auth.substring(BEARER_PREFIX.length()).trim();
	}

	private void notifyListeners(java.util.function.Consumer<AgentLifecycleListener> fn) {
		for (AgentLifecycleListener l : listeners) {
			try {
				fn.accept(l);
			} catch (Exception e) {
				log.warn("listener invocation failed: {}", e.toString());
			}
		}
	}

	/**
	 * Heartbeat 도착 시 identityStore 의 lastSeenAt + lastK8sApiOkAt 업데이트. Best-effort — DB 실패가
	 * stream 끊지 않음.
	 */
	private void updateAgentHeartbeat(String clusterName, Heartbeat heartbeat) {
		try {
			Instant lastSeen = clock.instant();
			Instant lastK8sOk = null;
			if (heartbeat != null && heartbeat.hasHealth() && heartbeat.getHealth().hasLastK8SApiOk()) {
				var ts = heartbeat.getHealth().getLastK8SApiOk();
				lastK8sOk = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
			}
			identityStore.updateLastSeen(clusterName, lastSeen, lastK8sOk);
		} catch (Exception e) {
			log.debug("heartbeat update skipped cluster={}: {}", clusterName, e.toString());
		}
	}

	/** Inbound observer — agent 가 보낸 AgentMessage 처리. */
	private class AgentObserver implements StreamObserver<AgentMessage> {
		private final AgentSession session;

		AgentObserver(AgentSession session) {
			this.session = session;
		}

		@Override
		public void onNext(AgentMessage msg) {
			try (MDC.MDCCloseable c = MDC.putCloseable("cluster", session.clusterId())) {
				dispatch(msg);
			} catch (Exception e) {
				log.error("Failed to dispatch AgentMessage cluster={}: {}",
						session.clusterId(), e.toString(), e);
			}
		}

		@Override
		public void onError(Throwable t) {
			log.info("Runtime stream error cluster={}: {}", session.clusterId(), t.toString());
			sessionRegistry.unregister(session);
			notifyListeners(l -> l.onStreamDisconnected(session.clusterId(), session.agentInstanceId()));
		}

		@Override
		public void onCompleted() {
			log.info("Runtime stream completed cluster={}", session.clusterId());
			sessionRegistry.unregister(session);
			try {
				session.downstream().onCompleted();
			} catch (Exception ignored) {
			}
			notifyListeners(l -> l.onStreamDisconnected(session.clusterId(), session.agentInstanceId()));
		}

		private void dispatch(AgentMessage msg) {
			String requestId = msg.getRequestId();
			switch (msg.getPayloadCase()) {
				case RESPONSE -> {
					if (!requestId.isEmpty()) {
						sessionRegistry.completeResponse(requestId, msg.getResponse());
					}
				}
				case HEARTBEAT -> {
					sessionRegistry.onUnsolicited(session, msg);
					updateAgentHeartbeat(session.clusterId(), msg.getHeartbeat());
					notifyListeners(l -> l.onHeartbeat(session.clusterId(), msg.getHeartbeat()));
				}
				case EVENT -> {
					log.debug("AgentEvent cluster={} type={}",
							session.clusterId(), msg.getEvent().getEventType());
					sessionRegistry.onUnsolicited(session, msg);
					notifyListeners(l -> l.onAgentEvent(session.clusterId(), msg.getEvent()));
				}
				case PAYLOAD_NOT_SET -> log.debug("AgentMessage with no payload cluster={}",
						session.clusterId());
			}
		}
	}

	/** Auth 실패 시 사용 — incoming message 모두 무시. */
	private static class NoopAgentObserver implements StreamObserver<AgentMessage> {
		@Override public void onNext(AgentMessage value) {}
		@Override public void onError(Throwable t) {}
		@Override public void onCompleted() {}
	}

	private static class NoopExecObserver implements StreamObserver<ExecPacket> {
		@Override public void onNext(ExecPacket value) {}
		@Override public void onError(Throwable t) {}
		@Override public void onCompleted() {}
	}

	/**
	 * Agent → backend PodExec bidi stream 의 inbound observer.
	 *
	 * <p>첫 packet 으로 ExecRequest 가 와야 session_id 추출 가능. 이후 packet 은 bridge 로 forward.
	 */
	private class ExecObserver implements StreamObserver<ExecPacket> {
		private final StreamObserver<ExecPacket> toAgent;
		private final String clusterName;
		private volatile ExecBridge bridge;
		private volatile String sessionId;

		ExecObserver(StreamObserver<ExecPacket> toAgent, String clusterName) {
			this.toAgent = toAgent;
			this.clusterName = clusterName;
		}

		@Override
		public void onNext(ExecPacket pkt) {
			if (bridge == null) {
				if (pkt.getPayloadCase() != ExecPacket.PayloadCase.REQUEST) {
					log.warn("PodExec: first packet not Request (cluster={}, case={})",
							clusterName, pkt.getPayloadCase());
					toAgent.onError(Status.INVALID_ARGUMENT
							.withDescription("first packet must be ExecRequest")
							.asRuntimeException());
					return;
				}
				sessionId = pkt.getRequest().getSessionId();
				if (sessionId == null || sessionId.isBlank()) {
					toAgent.onError(Status.INVALID_ARGUMENT
							.withDescription("ExecRequest.session_id required")
							.asRuntimeException());
					return;
				}
				bridge = execSessionRegistry.bindAgentStream(sessionId, toAgent);
				if (bridge == null) {
					log.warn("PodExec: session {} unknown or already bound (cluster={})",
							sessionId, clusterName);
					toAgent.onError(Status.NOT_FOUND
							.withDescription("exec session unknown or already bound")
							.asRuntimeException());
					return;
				}
				log.info("PodExec stream bound cluster={} session={}", clusterName, sessionId);
				return;
			}
			bridge.handlePacketFromAgent(pkt);
		}

		@Override
		public void onError(Throwable t) {
			log.info("PodExec stream error session={}: {}", sessionId, t.toString());
			if (bridge != null) {
				bridge.onAgentError(t);
				execSessionRegistry.remove(sessionId);
			}
		}

		@Override
		public void onCompleted() {
			log.info("PodExec stream completed session={}", sessionId);
			if (bridge != null) {
				execSessionRegistry.remove(sessionId);
			}
			try {
				toAgent.onCompleted();
			} catch (Exception ignored) {
			}
		}
	}

	/** {@link #streamPodLogs} 가 반환하는 noop fallback (auth 실패 시). */
	private static class NoopLogStreamObserver implements StreamObserver<LogPacket> {
		@Override public void onNext(LogPacket value) {}
		@Override public void onError(Throwable t) {}
		@Override public void onCompleted() {}
	}

	/**
	 * Agent → backend StreamPodLogs bidi stream 의 inbound observer.
	 *
	 * <p>첫 packet 으로 LogStreamRequest 가 와야 session_id 추출 가능. 이후 packet (LogChunk)
	 * 은 bridge 로 forward → SSE callbacks 로 emit.
	 *
	 * <p>{@link ExecObserver} 와 거의 동일 — payload type 만 다름.
	 */
	private class LogStreamObserver implements StreamObserver<LogPacket> {
		private final StreamObserver<LogPacket> toAgent;
		private final String clusterName;
		private volatile LogStreamBridge bridge;
		private volatile String sessionId;

		LogStreamObserver(StreamObserver<LogPacket> toAgent, String clusterName) {
			this.toAgent = toAgent;
			this.clusterName = clusterName;
		}

		@Override
		public void onNext(LogPacket pkt) {
			if (bridge == null) {
				if (pkt.getPayloadCase() != LogPacket.PayloadCase.REQUEST) {
					log.warn("StreamPodLogs: first packet not Request (cluster={}, case={})",
							clusterName, pkt.getPayloadCase());
					toAgent.onError(Status.INVALID_ARGUMENT
							.withDescription("first packet must be LogStreamRequest")
							.asRuntimeException());
					return;
				}
				sessionId = pkt.getRequest().getSessionId();
				if (sessionId == null || sessionId.isBlank()) {
					toAgent.onError(Status.INVALID_ARGUMENT
							.withDescription("LogStreamRequest.session_id required")
							.asRuntimeException());
					return;
				}
				bridge = logStreamSessionRegistry.bindAgentStream(sessionId, toAgent);
				if (bridge == null) {
					log.warn("StreamPodLogs: session {} unknown or already bound (cluster={})",
							sessionId, clusterName);
					toAgent.onError(Status.NOT_FOUND
							.withDescription("log stream session unknown or already bound")
							.asRuntimeException());
					return;
				}
				log.info("StreamPodLogs bound cluster={} session={}", clusterName, sessionId);
				return;
			}
			bridge.handlePacketFromAgent(pkt);
		}

		@Override
		public void onError(Throwable t) {
			log.info("StreamPodLogs error session={}: {}", sessionId, t.toString());
			if (bridge != null) {
				bridge.onAgentError(t);
			}
			if (sessionId != null) {
				logStreamSessionRegistry.remove(sessionId);
			}
		}

		@Override
		public void onCompleted() {
			log.info("StreamPodLogs completed session={}", sessionId);
			if (bridge != null) {
				bridge.onAgentCompleted();
			}
			if (sessionId != null) {
				logStreamSessionRegistry.remove(sessionId);
			}
			try {
				toAgent.onCompleted();
			} catch (Exception ignored) {
			}
		}
	}
}
