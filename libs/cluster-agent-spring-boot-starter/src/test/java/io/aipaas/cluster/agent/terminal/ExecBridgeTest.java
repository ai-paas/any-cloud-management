package io.aipaas.cluster.agent.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.v1.ExecPacket;
import io.aipaas.cluster.agent.v1.ExecStatus;
import io.aipaas.cluster.agent.v1.TerminalSize;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ExecBridge 의 양방향 packet 라우팅 회귀 보호.
 */
class ExecBridgeTest  {

	private RecordingObserver toAgent;
	private RecordingCallbacks callbacks;
	private ExecBridge bridge;

	@BeforeEach
	void setUp() {
		bridge = new ExecBridge("sess-1");
		toAgent = new RecordingObserver();
		callbacks = new RecordingCallbacks();
		bridge.setCallbacks(callbacks);
	}

	@Test
	void bindAgent_idempotentlyRejectsDuplicates() {
		assertThat(bridge.bindAgent(toAgent)).isTrue();
		assertThat(bridge.bindAgent(new RecordingObserver())).isFalse();
		assertThat(bridge.isAgentBound()).isTrue();
	}

	@Test
	void sendStdinFromUser_pushesToAgentObserver() {
		bridge.bindAgent(toAgent);
		bridge.sendStdinFromUser("hello".getBytes());

		assertThat(toAgent.packets).hasSize(1);
		ExecPacket pkt = toAgent.packets.get(0);
		assertThat(pkt.getPayloadCase()).isEqualTo(ExecPacket.PayloadCase.STDIN_DATA);
		assertThat(pkt.getStdinData().toStringUtf8()).isEqualTo("hello");
	}

	@Test
	void sendResizeFromUser_pushesResizePacket() {
		bridge.bindAgent(toAgent);
		bridge.sendResizeFromUser(120, 40);

		assertThat(toAgent.packets).hasSize(1);
		TerminalSize ts = toAgent.packets.get(0).getResize();
		assertThat(ts.getCols()).isEqualTo(120);
		assertThat(ts.getRows()).isEqualTo(40);
	}

	@Test
	void handlePacketFromAgent_stdout_invokesCallback() {
		bridge.bindAgent(toAgent);
		bridge.handlePacketFromAgent(ExecPacket.newBuilder()
				.setStdoutData(ByteString.copyFromUtf8("out-bytes"))
				.build());
		assertThat(callbacks.stdoutChunks).containsExactly("out-bytes".getBytes());
	}

	@Test
	void handlePacketFromAgent_end_invokesCallbackAndMarksClosed() {
		bridge.bindAgent(toAgent);
		ExecStatus end = ExecStatus.newBuilder().setExitCode(42).setMessage("done").build();
		bridge.handlePacketFromAgent(ExecPacket.newBuilder().setEnd(end).build());

		assertThat(callbacks.endStatus).isNotNull();
		assertThat(callbacks.endStatus.getExitCode()).isEqualTo(42);
		assertThat(bridge.isClosed()).isTrue();
	}

	@Test
	void sendAfterClose_isNoop() {
		bridge.bindAgent(toAgent);
		bridge.closeFromUser();
		bridge.sendStdinFromUser("late".getBytes());
		// closeFromUser sends onCompleted; subsequent sends must not generate new packets.
		assertThat(toAgent.packets).isEmpty();
		assertThat(toAgent.completed).isTrue();
	}

	@Test
	void onAgentError_propagatesStatusToCallback() {
		bridge.bindAgent(toAgent);
		bridge.onAgentError(new RuntimeException("boom"));
		assertThat(callbacks.endStatus).isNotNull();
		assertThat(callbacks.endStatus.getErrorCode()).isEqualTo("AGENT_STREAM_ERROR");
		assertThat(callbacks.endStatus.getMessage()).contains("boom");
	}

	@Test
	void markFailed_emitsAgentUnavailable() {
		bridge.markFailed("no agent online");
		assertThat(callbacks.endStatus.getErrorCode()).isEqualTo("AGENT_UNAVAILABLE");
		assertThat(callbacks.endStatus.getMessage()).contains("no agent online");
	}

	// -------- helpers --------

	private static class RecordingObserver implements StreamObserver<ExecPacket> {
		final List<ExecPacket> packets = new ArrayList<>();
		volatile boolean completed = false;
		@Override public void onNext(ExecPacket value) { packets.add(value); }
		@Override public void onError(Throwable t) {}
		@Override public void onCompleted() { completed = true; }
	}

	private static class RecordingCallbacks implements ExecBridge.Callbacks {
		final List<byte[]> stdoutChunks = new ArrayList<>();
		final List<byte[]> stderrChunks = new ArrayList<>();
		volatile ExecStatus endStatus;
		@Override public void onStdout(byte[] data) { stdoutChunks.add(data); }
		@Override public void onStderr(byte[] data) { stderrChunks.add(data); }
		@Override public void onEnd(ExecStatus status) { endStatus = status; }
	}
}
