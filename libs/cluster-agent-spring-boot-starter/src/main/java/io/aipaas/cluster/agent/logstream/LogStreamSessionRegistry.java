package io.aipaas.cluster.agent.logstream;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE controller ↔ gRPC StreamPodLogs bridge 의 세션 매핑소.
 *
 * <p>패턴 — {@link io.aipaas.cluster.agent.terminal.ExecSessionRegistry} 와 동일:
 * <ol>
 *   <li>SSE controller 가 {@link #createPending(Duration)} 호출 → session_id + bridge 발급</li>
 *   <li>SSE controller 가 bridge 에 callbacks 등록 후 {@link
 *       io.aipaas.cluster.agent.runtime.AgentSessionRegistry#openLogStream} 으로 agent 에 push</li>
 *   <li>Agent 가 새 StreamPodLogs bidi 호출 + 첫 LogPacket{Request, session_id} 송신</li>
 *   <li>Backend StreamPodLogs gRPC handler 가 session_id 로 pending bridge lookup →
 *       {@link #bindAgentStream(String, io.grpc.stub.StreamObserver)} 으로 attach</li>
 *   <li>이후 양방향 — agent → bridge → SSE callbacks (chunk), SSE cancel → bridge → agent stream close</li>
 * </ol>
 *
 * <p>Pending 이 일정 시간 안에 bound 되지 않으면 expire → SSE 에 실패 통지. 기본 30 초.
 */
@Slf4j
public class LogStreamSessionRegistry {

	private final Map<String, LogStreamBridge> pendingBySession = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
		Thread t = new Thread(r, "logstream-session-expirer");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 새 pending session. 호출자 (SSE controller) 가 bridge 에 callbacks set 후 agent 에 OpenLogStream push.
	 */
	public PendingLogStreamSession createPending(Duration expireAfter) {
		String sessionId = UUID.randomUUID().toString();
		LogStreamBridge bridge = new LogStreamBridge(sessionId);
		pendingBySession.put(sessionId, bridge);

		scheduler.schedule(() -> {
			LogStreamBridge existing = pendingBySession.get(sessionId);
			if (existing != null && !existing.isAgentBound()) {
				pendingBySession.remove(sessionId);
				existing.markFailed("timeout waiting for agent to open log stream");
				log.warn("Log stream session {} expired (agent never connected)", sessionId);
			}
		}, expireAfter.toMillis(), TimeUnit.MILLISECONDS);

		return new PendingLogStreamSession(sessionId, bridge);
	}

	/** Agent stream 의 첫 packet 도착 시 호출. session_id 매칭되면 bridge 반환 + bind. */
	public LogStreamBridge bindAgentStream(String sessionId,
			io.grpc.stub.StreamObserver<io.aipaas.cluster.agent.v1.LogPacket> agentObserver) {
		LogStreamBridge bridge = pendingBySession.remove(sessionId);
		if (bridge == null) {
			return null;
		}
		if (!bridge.bindAgent(agentObserver)) {
			return null;
		}
		return bridge;
	}

	/** Safety net — agent error / disconnect 시 cleanup. 이미 제거됐으면 no-op. */
	public void remove(String sessionId) {
		pendingBySession.remove(sessionId);
	}

	/** pending session 수 (모니터링/디버깅). */
	public int pendingCount() {
		return pendingBySession.size();
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}

	public record PendingLogStreamSession(String sessionId, LogStreamBridge bridge) {}
}
