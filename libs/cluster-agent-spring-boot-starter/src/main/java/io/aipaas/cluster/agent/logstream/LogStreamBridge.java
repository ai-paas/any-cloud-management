package io.aipaas.cluster.agent.logstream;

import io.aipaas.cluster.agent.v1.LogPacket;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 단일 Pod log streaming 세션의 bridge.
 *
 * <p>두 side 가 본 객체를 공유:
 * <ul>
 *   <li><b>SSE controller (consumer)</b>: {@link Callbacks} 로 chunk 수신. SSE client 가
 *       disconnect 하면 {@link #cancelFromConsumer()} 호출 → agent stream close.</li>
 *   <li><b>gRPC StreamPodLogs server</b>: agent → backend packet 을 {@link
 *       #handlePacketFromAgent(LogPacket)} 로 dispatch, {@link #bindAgent(StreamObserver)}
 *       으로 outbound observer 등록.</li>
 * </ul>
 *
 * <p>{@link io.aipaas.cluster.agent.terminal.ExecBridge} 와 동일 패턴. 차이점: log stream 은
 * stdin 방향 없음 — consumer → agent 흐름은 cancel signal 만.
 *
 * <p>Reactor / Flux 등 backend-side 도구는 의도적으로 미사용 — starter dep 가벼움 유지. 외부 backend 가
 * Callbacks 를 Reactor Sink 등에 bridge 책임.
 */
@Slf4j
public class LogStreamBridge {

	@Getter
	private final String sessionId;

	private final AtomicBoolean agentBound = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/** Agent 측 packet response observer (bidi 의 server→client 방향). cancel 시 onCompleted. */
	private volatile StreamObserver<LogPacket> toAgent;

	/** Consumer 가 set. agent chunk / complete / error 수신 콜백. */
	private volatile Callbacks callbacks;

	public LogStreamBridge(String sessionId) {
		this.sessionId = sessionId;
	}

	public boolean isAgentBound() {
		return agentBound.get();
	}

	/** Agent stream 도착 시 한 번 호출. duplicate 면 false. */
	public boolean bindAgent(StreamObserver<LogPacket> observer) {
		if (!agentBound.compareAndSet(false, true)) {
			return false;
		}
		this.toAgent = observer;
		return true;
	}

	/** Consumer (SSE controller) 가 콜백 set. 등록 직후 바로 호출 권장. */
	public void setCallbacks(Callbacks callbacks) {
		this.callbacks = callbacks;
	}

	/* ---------- agent → consumer 방향 ---------- */

	/** AgentRuntimeEndpoint.streamPodLogs 가 각 LogPacket 마다 호출. */
	public void handlePacketFromAgent(LogPacket pkt) {
		Callbacks cb = callbacks;
		if (cb == null) {
			return;
		}
		switch (pkt.getPayloadCase()) {
			case CHUNK -> {
				var chunk = pkt.getChunk();
				cb.onChunk(chunk.getData().toByteArray(), chunk.getStderr());
			}
			case REQUEST -> {
				// 첫 packet — gRPC server impl 이 session_id 추출 후 본 handler 호출 전 처리. 무시.
			}
			default -> log.debug("logstream bridge {}: unexpected packet {}", sessionId, pkt.getPayloadCase());
		}
	}

	/** Agent stream 정상 종료 (K8s EOF) — completed. */
	public void onAgentCompleted() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		Callbacks cb = callbacks;
		if (cb != null) {
			cb.onComplete();
		}
	}

	/** Agent stream 비정상 종료 (network drop, error). */
	public void onAgentError(Throwable t) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		Callbacks cb = callbacks;
		if (cb != null) {
			cb.onError(t);
		}
	}

	/** Pending session 이 timeout 으로 expire 됐을 때 호출 — agent 가 stream open 을 안 한 경우. */
	public void markFailed(String reason) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		Callbacks cb = callbacks;
		if (cb != null) {
			cb.onError(new IllegalStateException(reason));
		}
	}

	/* ---------- consumer → agent 방향 (cancel only) ---------- */

	/**
	 * Consumer (SSE controller) 가 client 의 disconnect 등으로 stream 종료를 원할 때 호출.
	 * Agent 측 response stream 을 close → agent 의 logstream.Runner 가 Recv EOF detect 후 cleanup.
	 */
	public void cancelFromConsumer() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		StreamObserver<LogPacket> sink = toAgent;
		if (sink != null) {
			try {
				synchronized (this) {
					sink.onCompleted();
				}
			} catch (Exception e) {
				log.debug("logstream bridge {}: cancel onCompleted failed: {}",
						sessionId, e.toString());
			}
		}
	}

	public boolean isClosed() {
		return closed.get();
	}

	/** SSE controller 가 구현. */
	public interface Callbacks {
		/** Agent 로부터 chunk 수신. stderr=true 면 stderr stream (include_stderr=true 일 때만 의미). */
		void onChunk(byte[] data, boolean stderr);
		/** K8s stream 정상 EOF. */
		void onComplete();
		/** 비정상 종료 (network drop / timeout / agent error). */
		void onError(Throwable t);
	}
}
