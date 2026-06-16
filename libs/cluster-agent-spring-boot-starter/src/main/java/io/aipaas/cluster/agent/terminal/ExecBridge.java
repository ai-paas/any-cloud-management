package io.aipaas.cluster.agent.terminal;

import io.aipaas.cluster.agent.v1.ExecPacket;
import io.aipaas.cluster.agent.v1.ExecStatus;
import io.aipaas.cluster.agent.v1.TerminalSize;
import com.google.protobuf.ByteString;
import io.aipaas.cluster.agent.core.ExecErrorCode;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 단일 PodExec 세션의 양방향 bridge.
 *
 * <p>두 side 가 본 객체를 공유:
 * <ul>
 *   <li><b>WebSocket handler</b>: user 입력 (stdin/resize) 을 {@link #sendStdinFromUser(byte[])} /
 *       {@link #sendResizeFromUser(int, int)} 로 agent 에 forward, agent 출력 (stdout/stderr/end) 을
 *       {@link Callbacks} 로 수신.</li>
 *   <li><b>gRPC PodExec server</b>: agent → backend packet 을 {@link
 *       #handlePacketFromAgent(ExecPacket)} 로 dispatch, {@link #bindAgent(StreamObserver)} 로
 *       outbound observer 등록.</li>
 * </ul>
 *
 * <p>Thread-safety: agent observer 의 {@code onNext} 는 본 클래스 안에서 synchronized. Callbacks 는
 * 단일 thread (WebSocket handler) 에서만 호출된다고 가정.
 */
@Slf4j
public class ExecBridge {

	@Getter
	private final String sessionId;

	private final AtomicBoolean agentBound = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/** Agent 로 패킷 보낼 outbound observer (server-side StreamObserver from gRPC). */
	private volatile StreamObserver<ExecPacket> toAgent;

	/** WebSocket handler 가 set. agent 응답 처리 콜백. */
	private volatile Callbacks callbacks;

	public ExecBridge(String sessionId) {
		this.sessionId = sessionId;
	}

	public boolean isAgentBound() {
		return agentBound.get();
	}

	/** Agent stream 도착 시 한 번 호출. duplicate 면 false. */
	boolean bindAgent(StreamObserver<ExecPacket> observer) {
		if (!agentBound.compareAndSet(false, true)) {
			return false;
		}
		this.toAgent = observer;
		return true;
	}

	/** WebSocket handler 가 콜백 set. WebSocket open 후 즉시 호출 권장. */
	public void setCallbacks(Callbacks callbacks) {
		this.callbacks = callbacks;
	}

	// ----- user → agent direction -----

	public void sendStdinFromUser(byte[] data) {
		StreamObserver<ExecPacket> sink = toAgent;
		if (sink == null || closed.get()) {
			return;
		}
		try {
			synchronized (this) {
				sink.onNext(ExecPacket.newBuilder()
						.setStdinData(ByteString.copyFrom(data))
						.build());
			}
		} catch (Exception e) {
			log.debug("exec bridge {}: stdin send failed: {}", sessionId, e.toString());
		}
	}

	public void sendResizeFromUser(int cols, int rows) {
		StreamObserver<ExecPacket> sink = toAgent;
		if (sink == null || closed.get()) {
			return;
		}
		try {
			synchronized (this) {
				sink.onNext(ExecPacket.newBuilder()
						.setResize(TerminalSize.newBuilder()
								.setCols(cols)
								.setRows(rows))
						.build());
			}
		} catch (Exception e) {
			log.debug("exec bridge {}: resize send failed: {}", sessionId, e.toString());
		}
	}

	/** WebSocket close 또는 user 명시 종료 시. agent 에게 stream completed 신호. */
	public void closeFromUser() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		StreamObserver<ExecPacket> sink = toAgent;
		if (sink != null) {
			try {
				synchronized (this) {
					sink.onCompleted();
				}
			} catch (Exception e) {
				log.debug("exec bridge {}: onCompleted failed: {}", sessionId, e.toString());
			}
		}
	}

	// ----- agent → user direction -----

	/** Agent gRPC server impl 이 매 ExecPacket 마다 호출. */
	public void handlePacketFromAgent(ExecPacket pkt) {
		Callbacks cb = callbacks;
		if (cb == null) {
			return;
		}
		switch (pkt.getPayloadCase()) {
			case STDOUT_DATA -> cb.onStdout(pkt.getStdoutData().toByteArray());
			case STDERR_DATA -> cb.onStderr(pkt.getStderrData().toByteArray());
			case END -> {
				cb.onEnd(pkt.getEnd());
				closed.set(true);
			}
			case REQUEST -> {
				// 첫 패킷 — gRPC server impl 이 session_id 추출 후 본 handler 호출 전 처리. 무시.
			}
			default -> log.debug("exec bridge {}: unexpected packet {}", sessionId, pkt.getPayloadCase());
		}
	}

	/** Agent stream 에러 (network drop / cancel) 시 호출. */
	public void onAgentError(Throwable t) {
		Callbacks cb = callbacks;
		if (cb != null) {
			cb.onEnd(ExecStatus.newBuilder()
					.setExitCode(-1)
					.setErrorCode(ExecErrorCode.AGENT_STREAM_ERROR.wire())
					.setMessage(t.toString())
					.build());
		}
		closed.set(true);
	}

	/** Pending session 이 timeout 으로 expire 됐을 때 호출. */
	public void markFailed(String reason) {
		Callbacks cb = callbacks;
		if (cb != null) {
			cb.onEnd(ExecStatus.newBuilder()
					.setExitCode(-1)
					.setErrorCode(ExecErrorCode.AGENT_UNAVAILABLE.wire())
					.setMessage(reason)
					.build());
		}
		closed.set(true);
	}

	public boolean isClosed() {
		return closed.get();
	}

	/** WebSocket handler 가 구현. */
	public interface Callbacks {
		void onStdout(byte[] data);
		void onStderr(byte[] data);
		void onEnd(ExecStatus status);
	}
}
