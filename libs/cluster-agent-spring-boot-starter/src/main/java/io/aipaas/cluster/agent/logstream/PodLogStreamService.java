package io.aipaas.cluster.agent.logstream;

import io.aipaas.cluster.agent.v1.LogStreamRequest;
import io.aipaas.cluster.agent.logstream.LogStreamSessionRegistry.PendingLogStreamSession;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pod log streaming 의 backend-facing 진입점.
 *
 * <p>한 번의 호출로:
 * <ol>
 *   <li>{@link LogStreamSessionRegistry#createPending} 으로 session_id + bridge 발급</li>
 *   <li>{@link AgentSessionRegistry#openLogStream} 으로 agent 에게 stream open 지시</li>
 *   <li>bridge 반환 — caller 가 {@link LogStreamBridge#setCallbacks} 로 chunk/complete/error 수신 콜백 등록</li>
 * </ol>
 *
 * <p>Caller (anycloud 의 SSE controller) 는 Reactor Flux 와 같은 backpressure-aware 추상으로
 * bridge 를 감싸 사용. Reactor dep 를 starter 에 넣지 않기 위해 본 facade 는 callback 만 노출.
 *
 * <p>{@link io.aipaas.cluster.agent.terminal.PodExecWebSocketHandler} 와 동일 패턴 — 그 쪽은
 * WebSocket 으로 양방향, 본 쪽은 SSE 로 한 방향 (agent → consumer).
 */
@Slf4j
@RequiredArgsConstructor
public class PodLogStreamService {

	private static final Duration DEFAULT_BIND_TIMEOUT = Duration.ofSeconds(30);

	private final LogStreamSessionRegistry sessionRegistry;
	private final AgentSessionRegistry agentSessionRegistry;

	/** routing flag — false 면 항상 {@link #isActiveFor} = false. */
	private final boolean enabled;

	/**
	 * 새 log stream 세션을 열고 bridge 반환. Caller 가 즉시 bridge 에 callbacks 등록 권장.
	 *
	 * @return bridge, 또는 agent active session 없으면 null.
	 */
	public LogStreamBridge openStream(String clusterName, LogStreamRequest request) {
		if (!enabled) {
			log.debug("openStream: routing disabled — caller fallback");
			return null;
		}
		PendingLogStreamSession pending = sessionRegistry.createPending(DEFAULT_BIND_TIMEOUT);
		// session_id 를 request 에 echo (agent 가 첫 LogPacket{Request} 로 다시 보냄).
		LogStreamRequest enriched = LogStreamRequest.newBuilder(request)
				.setSessionId(pending.sessionId())
				.build();
		boolean pushed = agentSessionRegistry.openLogStream(clusterName, pending.sessionId(), enriched);
		if (!pushed) {
			sessionRegistry.remove(pending.sessionId());
			log.warn("openStream: no active agent session cluster={}", clusterName);
			return null;
		}
		log.info("Log stream opened cluster={} session={}", clusterName, pending.sessionId());
		return pending.bridge();
	}

	/** routing flag ON + active agent session 있으면 true. */
	public boolean isActiveFor(String clusterName) {
		if (!enabled) {
			return false;
		}
		return agentSessionRegistry.find(clusterName).isPresent();
	}
}
