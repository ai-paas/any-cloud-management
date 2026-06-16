package io.aipaas.cluster.agent.terminal;

import io.aipaas.cluster.agent.v1.ExecPacket;
import io.grpc.stub.StreamObserver;
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
 * WebSocket ↔ gRPC PodExec bridge 의 세션 매핑소.
 *
 * <p><b>왜 필요한가</b>: agent 는 reverse tunnel 이므로 backend 에서 직접 PodExec 호출 불가. 대신 다음
 * 패턴:
 * <ol>
 *   <li>User WebSocket open → backend 가 {@link #createPending(Duration)} 호출 → session_id 발급</li>
 *   <li>Backend 가 agent 에게 OpenExecSession control message push (기존 Stream RPC 통해)</li>
 *   <li>Agent 가 새 PodExec bidi stream open + 첫 ExecPacket{Request, session_id} 송신</li>
 *   <li>Backend PodExec gRPC handler 가 session_id 로 pending bridge lookup → {@link
 *       #bindAgentStream(String, StreamObserver)} 로 attach</li>
 *   <li>WebSocket handler 와 agent stream 이 ExecBridge 객체를 공유해 양방향 packet 흘려보냄</li>
 * </ol>
 *
 * <p><b>Timeout</b>: pending session 이 일정 시간 안에 agent stream 으로 bound 되지 않으면 stale 로
 * expire 시켜 WebSocket 에 실패 신호. 기본 30 초.
 */
@Slf4j
public class ExecSessionRegistry {

	private final Map<String, ExecBridge> pendingBySession = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
		Thread t = new Thread(r, "exec-session-expirer");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 새 pending session 등록. session_id 와 ExecBridge 를 반환. 호출자(WebSocket handler) 가 ExecBridge
	 * 에 stdout/stderr/end 수신 콜백을 set 한 뒤 agent 에게 OpenExecSession 을 push.
	 *
	 * @param expireAfter agent 가 stream 을 열어 bind 할 때까지 허용된 시간
	 */
	public PendingExecSession createPending(Duration expireAfter) {
		String sessionId = UUID.randomUUID().toString();
		ExecBridge bridge = new ExecBridge(sessionId);
		pendingBySession.put(sessionId, bridge);

		// Expire 처리 — 시간 초과 시 pending 제거. 단 이미 bound 됐으면 noop.
		scheduler.schedule(() -> {
			ExecBridge existing = pendingBySession.get(sessionId);
			if (existing != null && !existing.isAgentBound()) {
				pendingBySession.remove(sessionId);
				existing.markFailed("timeout waiting for agent to open exec stream");
				log.warn("Exec session {} expired (agent never connected)", sessionId);
			}
		}, expireAfter.toMillis(), TimeUnit.MILLISECONDS);

		return new PendingExecSession(sessionId, bridge);
	}

	/**
	 * Agent 의 PodExec gRPC stream 이 첫 packet 으로 ExecRequest 를 보냈을 때 호출.
	 *
	 * @return null 이면 session 이 unknown/expired — caller 가 stream 닫아야 함.
	 */
	public ExecBridge bindAgentStream(String sessionId, StreamObserver<ExecPacket> toAgent) {
		ExecBridge bridge = pendingBySession.get(sessionId);
		if (bridge == null) {
			return null;
		}
		if (!bridge.bindAgent(toAgent)) {
			// 이미 다른 stream 이 bound — duplicate / replay 공격 방어.
			return null;
		}
		return bridge;
	}

	/** Bridge 종료 시 정리. WebSocket close 또는 agent stream end. */
	public void remove(String sessionId) {
		pendingBySession.remove(sessionId);
	}

	public int pendingCount() {
		return pendingBySession.size();
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}

	/** WebSocket handler 에게 반환되는 (session_id + bridge) 쌍. */
	public record PendingExecSession(String sessionId, ExecBridge bridge) {}
}
