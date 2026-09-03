package io.aipaas.cluster.agent.runtime;

import io.aipaas.cluster.agent.v1.AgentMessage;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.ExecRequest;
import io.aipaas.cluster.agent.v1.OpenExecSession;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 활성 agent runtime stream 의 in-memory registry — cluster_name → 활성 streams + 대기 command future.
 * <ul>
 *   <li>Process-local: multi-replica backend 는 sticky session 또는 broker fan-out 필요</li>
 *   <li>HA: 같은 cluster 의 여러 agent stream 보존 (leader = 가장 오래된 stream)</li>
 *   <li>Command dispatch: request_id ↔ CompletableFuture 매핑, timeout 자동 처리</li>
 * </ul>
 *
 * <p>{@code pendingByRequest} 는 Caffeine bounded cache — stuck agent 시 OOM 방지.
 * {@code sessionsByCluster} 의 빈 list cleanup 은 atomic 하게 처리.
 */
@Slf4j
public class AgentSessionRegistry {

	/** pending command 최대 보존 (sanity guard). 초당 100 cmd × 60s timeout = 6000 이라 여유. */
	private static final int PENDING_MAX_SIZE = 10_000;
	/** pending 의 absolute deadline. {@code orTimeout} 보다 길게 잡아 cleanup race 회피. */
	private static final Duration PENDING_MAX_LIFETIME = Duration.ofMinutes(5);

	/** cluster_name → 활성 streams. Leader = list[0] (가장 오래된). CopyOnWriteArrayList = read lock-free. */
	private final Map<String, List<AgentSession>> sessionsByCluster = new ConcurrentHashMap<>();

	/**
	 * request_id → pending future. Cross-cluster (dispatch concurrent).
	 *
	 * <p>Caffeine bounded cache 사용 — {@code maximumSize} + {@code expireAfterWrite}.
	 * 만료된 entry 의 future 는 SessionClosedException 으로 fail (caller 가 timeout 인식).
	 */
	private final Cache<String, PendingCommand> pendingByRequest = Caffeine.newBuilder()
			.maximumSize(PENDING_MAX_SIZE)
			.expireAfterWrite(PENDING_MAX_LIFETIME)
			.removalListener((String key, PendingCommand cmd, RemovalCause cause) -> {
				if (cmd != null && !cmd.future().isDone()
						&& (cause == RemovalCause.SIZE || cause == RemovalCause.EXPIRED)) {
					// Caller 의 orTimeout 이 보통 먼저 발화 — 여기 도달은 race 또는 cap hit.
					cmd.future().completeExceptionally(new SessionClosedException(
							"pending command evicted from registry (cause=" + cause + ")"));
					log.warn("Pending command evicted request_id={} cause={}", key, cause);
				}
			})
			.build();

	/** Stream open 직후 호출 — HA replicas 보존. 같은 instance_id 재연결 시 stale stream 제거 후 추가. */
	public AgentSession register(String clusterName, String agentInstanceId,
			StreamObserver<ControlMessage> downstream) {
		AgentSession session = new AgentSession(
				clusterName, agentInstanceId, downstream, System.currentTimeMillis());

		List<AgentSession> list = sessionsByCluster.computeIfAbsent(clusterName,
				k -> new CopyOnWriteArrayList<>());

		// 같은 instance_id 의 stale stream 정리 (agent restart 후 backend 가 아직 onCompleted 미수신).
		list.removeIf(s -> {
			if (agentInstanceId.equals(s.agentInstanceId())) {
				try {
					s.downstream().onCompleted();
				} catch (Exception ignored) {
				}
				log.info("Stale session removed cluster={} instance={} (re-register)",
						clusterName, agentInstanceId);
				return true;
			}
			return false;
		});

		list.add(session);
		log.info("Agent session registered cluster={} instance={} replicas={} (leader={})",
				clusterName, agentInstanceId, list.size(), list.get(0).agentInstanceId());
		return session;
	}

	/**
	 * Stream 종료 — list 에서 제거. 만약 leader 였다면 다음 oldest 가 자동 leader 승격.
	 *
	 * <p>이 stream 의 pending command 들은 모두 cancel (다른 instance 로 retry 는 caller 책임).
	 */
	public void unregister(AgentSession session) {
		List<AgentSession> list = sessionsByCluster.get(session.clusterId());
		if (list == null) {
			return;
		}
		boolean removed = list.remove(session);
		// atomic empty-list cleanup.
		sessionsByCluster.computeIfPresent(session.clusterId(), (k, v) -> v.isEmpty() ? null : v);
		log.info("Agent session unregistered cluster={} instance={} removed={} replicas={}",
				session.clusterId(), session.agentInstanceId(), removed,
				list.size());

		// 이 stream 의 pending command 들을 모두 cancel.
		Map<String, PendingCommand> snapshot = pendingByRequest.asMap();
		List<String> toCancel = new ArrayList<>();
		for (Map.Entry<String, PendingCommand> entry : snapshot.entrySet()) {
			if (entry.getValue().clusterId().equals(session.clusterId())
					&& session.equals(entry.getValue().leaderAtSubmit())) {
				entry.getValue().future().completeExceptionally(
						new SessionClosedException("Leader stream closed for cluster " + session.clusterId()));
				toCancel.add(entry.getKey());
			}
		}
		for (String key : toCancel) {
			pendingByRequest.invalidate(key);
		}
	}

	/**
	 * cluster 의 leader session. find().isPresent() 가 health check 의 streamActive 판정에
	 * 사용되어 호환 유지.
	 */
	public Optional<AgentSession> find(String clusterName) {
		List<AgentSession> list = sessionsByCluster.get(clusterName);
		if (list == null || list.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(list.get(0));     // oldest = leader
	}

	/** cluster 의 모든 active session (HA replicas 포함). */
	public List<AgentSession> findAll(String clusterName) {
		List<AgentSession> list = sessionsByCluster.get(clusterName);
		return list == null ? List.of() : new ArrayList<>(list);
	}

	/**
	 * AAAA (Sprint 4 후속) — cluster 의 모든 active stream 을 강제 종료. revocation / forced
	 * disconnect 시 운영자 호출. agent 가 즉시 PERMISSION_DENIED 받고 next reconnect 시도 시 인증 실패
	 * (revoked_at 마킹 때문).
	 *
	 * <p>구현: downstream {@link StreamObserver#onError} 로 PERMISSION_DENIED status 전송 → agent 의
	 * client-side stream 종료 + reconnect logic 발화. backend 측 sessions list 에서 unregister.
	 *
	 * @param clusterName 대상 cluster
	 * @param reason      log + agent 의 error message 에 포함
	 * @return 종료된 session 수 (0 = 활성 stream 없음)
	 */
	public int evictByCluster(String clusterName, String reason) {
		List<AgentSession> sessions = findAll(clusterName);
		String msg = "agent forcefully disconnected by admin"
				+ (reason == null || reason.isBlank() ? "" : ": " + reason);
		for (AgentSession session : sessions) {
			try {
				session.downstream().onError(
						io.grpc.Status.PERMISSION_DENIED.withDescription(msg).asRuntimeException());
			} catch (Exception e) {
				log.warn("Failed to send onError to session cluster={} instance={}: {}",
						clusterName, session.agentInstanceId(), e.getMessage());
			}
			unregister(session);
		}
		log.warn("Evicted {} active session(s) for cluster {} ({})", sessions.size(), clusterName, reason);
		return sessions.size();
	}

	public Collection<AgentSession> allActive() {
		// 모든 cluster 의 leader 만 (호환 유지 — 기존 호출자는 cluster 당 1개 가정).
		List<AgentSession> leaders = new ArrayList<>();
		for (List<AgentSession> list : sessionsByCluster.values()) {
			if (!list.isEmpty()) {
				leaders.add(list.get(0));
			}
		}
		return leaders;
	}

	public int size() {
		// 총 cluster 수 (leader 기준).
		return sessionsByCluster.size();
	}

	/**
	 * Cluster 의 active session 에 명령을 push 하고 응답을 future 로 반환.
	 *
	 * @param clusterName 대상 cluster
	 * @param controlBuilder request_id 가 곧 set 되므로 caller 는 payload 만 채워 builder 로 넘김
	 * @param timeoutSeconds 응답 대기 최대 시간 (예: 30)
	 * @return Agent 가 보낸 CommandResponse (또는 timeout/예외)
	 */
	public CompletableFuture<CommandResponse> sendCommand(String clusterName,
			ControlMessage.Builder controlBuilder, int timeoutSeconds) {
		// Single-instance 가정. multi-instance 시 gateway sticky session 권장. 다른 instance 가
		// owner 인 cluster 명령은 LB sticky 없이 도착하면 NoActiveSession 으로 fail.
		// 자세히는 docs/architecture/cluster-agent.md#backend-session-registry §0.
		Optional<AgentSession> leaderOpt = find(clusterName);
		if (leaderOpt.isEmpty()) {
			CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
			failed.completeExceptionally(new NoActiveSessionException(
					"No active agent session for cluster: " + clusterName));
			return failed;
		}
		AgentSession session = leaderOpt.get();

		String requestId = UUID.randomUUID().toString();
		ControlMessage control = controlBuilder.setRequestId(requestId).build();

		CompletableFuture<CommandResponse> future = new CompletableFuture<>();
		pendingByRequest.put(requestId,
				new PendingCommand(clusterName, future, System.currentTimeMillis(), session));

		future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
				.whenComplete((r, ex) -> pendingByRequest.invalidate(requestId));

		try {
			synchronized (session) {     // StreamObserver onNext 는 thread-safe 보장 안 함.
				session.downstream().onNext(control);
			}
		} catch (Exception e) {
			pendingByRequest.invalidate(requestId);
			future.completeExceptionally(e);
		}
		return future;
	}

	/**
	 * Agent 가 보낸 응답을 매칭하는 pending future 에 전달. request_id 가 unknown 이면 drop (warning).
	 */
	public void completeResponse(String requestId, CommandResponse response) {
		PendingCommand pending = pendingByRequest.getIfPresent(requestId);
		if (pending == null) {
			log.warn("Received response for unknown request_id={} (already timed out or unknown agent)",
					requestId);
			return;
		}
		pendingByRequest.invalidate(requestId);
		pending.future().complete(response);
	}

	/**
	 * Agent 에게 별도 PodExec stream 을 열도록 지시.
	 *
	 * <p>{@link #sendCommand} 와 달리 응답을 기다리지 않는다. Agent 가 trigger 를 받으면 새 PodExec
	 * bidi RPC 를 outbound 로 open 하고, ExecPacket{Request, session_id} 를 첫 패킷으로 보냄. Backend
	 * 의 PodExec gRPC handler 는 session_id 로 pending WebSocket bridge 와 매칭.
	 *
	 * @return true 면 push 성공, false 면 active session 없음.
	 */
	public boolean openExecSession(String clusterName, String sessionId, ExecRequest request) {
		// leader 만 사용.
		Optional<AgentSession> leaderOpt = find(clusterName);
		if (leaderOpt.isEmpty()) {
			log.warn("openExecSession: no active session for cluster={}", clusterName);
			return false;
		}
		AgentSession session = leaderOpt.get();
		ControlMessage control = ControlMessage.newBuilder()
				.setRequestId(UUID.randomUUID().toString())
				.setOpenExecSession(OpenExecSession.newBuilder()
						.setSessionId(sessionId)
						.setRequest(request))
				.build();
		try {
			synchronized (session) {
				session.downstream().onNext(control);
			}
			return true;
		} catch (Exception e) {
			log.warn("openExecSession: push failed cluster={}: {}", clusterName, e.toString());
			return false;
		}
	}

	/**
	 * Agent 에게 별도 StreamPodLogs stream 을 열도록 지시. {@link #openExecSession} 의 log streaming 쌍.
	 *
	 * <p>Agent 가 trigger 를 받으면 새 StreamPodLogs bidi RPC 를 outbound 로 open 하고,
	 * LogPacket{Request, session_id} 를 첫 패킷으로 보냄. Backend 의 StreamPodLogs gRPC handler 는
	 * session_id 로 pending SSE bridge 와 매칭.
	 *
	 * @return true 면 push 성공, false 면 active session 없음.
	 */
	public boolean openLogStream(String clusterName, String sessionId,
			io.aipaas.cluster.agent.v1.LogStreamRequest request) {
		Optional<AgentSession> leaderOpt = find(clusterName);
		if (leaderOpt.isEmpty()) {
			log.warn("openLogStream: no active session for cluster={}", clusterName);
			return false;
		}
		AgentSession session = leaderOpt.get();
		ControlMessage control = ControlMessage.newBuilder()
				.setRequestId(UUID.randomUUID().toString())
				.setOpenLogStream(io.aipaas.cluster.agent.v1.OpenLogStream.newBuilder()
						.setSessionId(sessionId)
						.setRequest(request))
				.build();
		try {
			synchronized (session) {
				session.downstream().onNext(control);
			}
			return true;
		} catch (Exception e) {
			log.warn("openLogStream: push failed cluster={}: {}", clusterName, e.toString());
			return false;
		}
	}

	/** 외부에서 Agent 에 unsolicited (request_id 없음) AgentMessage 처리 — heartbeat / event 등. */
	public void onUnsolicited(AgentSession session, AgentMessage msg) {
		session.touch();
	}

	public record AgentSession(
			String clusterId,
			String agentInstanceId,
			StreamObserver<ControlMessage> downstream,
			long establishedAtEpochMillis) {
		void touch() {
			// last activity timestamp 갱신은 별도 mutable field 가 필요 (현재 stub).
		}
	}

	private record PendingCommand(
			String clusterId,
			CompletableFuture<CommandResponse> future,
			long submittedAtEpochMillis,
			/** leader 가 끊겼을 때만 cancel (다른 instance unregister 는 무영향). */
			AgentSession leaderAtSubmit) {}

	public static class NoActiveSessionException extends RuntimeException {
		public NoActiveSessionException(String message) {
			super(message);
		}
	}

	public static class SessionClosedException extends RuntimeException {
		public SessionClosedException(String message) {
			super(message);
		}
	}
}
