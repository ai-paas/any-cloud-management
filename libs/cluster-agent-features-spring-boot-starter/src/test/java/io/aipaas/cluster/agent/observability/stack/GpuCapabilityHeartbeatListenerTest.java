package io.aipaas.cluster.agent.observability.stack;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.aipaas.cluster.agent.v1.AgentHealth;
import io.aipaas.cluster.agent.v1.Heartbeat;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Heartbeat → ClusterCapabilitiesSink backfill 의 변경 감지 / 예외 safety / disconnect 처리.
 */
class GpuCapabilityHeartbeatListenerTest {

	private ClusterCapabilitiesSink sink;
	private GpuCapabilityHeartbeatListener listener;

	@BeforeEach
	void setUp() {
		sink = Mockito.mock(ClusterCapabilitiesSink.class);
		listener = new GpuCapabilityHeartbeatListener(sink);
	}

	private Heartbeat heartbeat(int gpuCount) {
		return Heartbeat.newBuilder()
				.setHealth(AgentHealth.newBuilder().setGpuNodeCount(gpuCount))
				.build();
	}

	@Test
	void onHeartbeat_firstReport_callsSink() {
		listener.onHeartbeat("c1", heartbeat(2));
		verify(sink).setHasGpuNodes("c1", true);
	}

	@Test
	void onHeartbeat_gpuCountZero_reportsFalse() {
		listener.onHeartbeat("c1", heartbeat(0));
		verify(sink).setHasGpuNodes("c1", false);
	}

	@Test
	void onHeartbeat_sameValueRepeated_callsSinkOnlyOnce() {
		listener.onHeartbeat("c1", heartbeat(2));
		listener.onHeartbeat("c1", heartbeat(2));
		listener.onHeartbeat("c1", heartbeat(3));     // 여전히 true — skip.
		listener.onHeartbeat("c1", heartbeat(0));     // false 로 전환 — write.

		verify(sink, times(1)).setHasGpuNodes("c1", true);
		verify(sink, times(1)).setHasGpuNodes("c1", false);
	}

	@Test
	void onHeartbeat_differentClusters_independentlyTracked() {
		listener.onHeartbeat("c1", heartbeat(1));
		listener.onHeartbeat("c2", heartbeat(0));
		listener.onHeartbeat("c1", heartbeat(1));     // c1 same — skip.
		listener.onHeartbeat("c2", heartbeat(0));     // c2 same — skip.

		verify(sink, times(1)).setHasGpuNodes("c1", true);
		verify(sink, times(1)).setHasGpuNodes("c2", false);
	}

	@Test
	void onHeartbeat_sinkThrows_swallowsAndAllowsRetry() {
		doThrow(new RuntimeException("DB down")).when(sink).setHasGpuNodes("c1", true);

		// 첫 호출 — 예외 swallow.
		listener.onHeartbeat("c1", heartbeat(2));
		// prev 가 갱신되지 않았으므로 다음 호출에서 다시 sink 시도.
		listener.onHeartbeat("c1", heartbeat(2));

		verify(sink, times(2)).setHasGpuNodes("c1", true);
	}

	@Test
	void onHeartbeat_nullHeartbeat_noOp() {
		listener.onHeartbeat("c1", null);
		verify(sink, never()).setHasGpuNodes(Mockito.anyString(), Mockito.anyBoolean());
	}

	@Test
	void onHeartbeat_noHealth_noOp() {
		listener.onHeartbeat("c1", Heartbeat.newBuilder().build());
		verify(sink, never()).setHasGpuNodes(Mockito.anyString(), Mockito.anyBoolean());
	}

	@Test
	void onStreamDisconnected_resetsCacheSoNextHeartbeatRepublishes() {
		listener.onHeartbeat("c1", heartbeat(2));
		listener.onStreamDisconnected("c1", "instance-1");

		// Reconnect 후 같은 값이라도 다시 sink 호출 — 운영 상태 강제 동기화.
		listener.onHeartbeat("c1", heartbeat(2));

		verify(sink, times(2)).setHasGpuNodes("c1", true);
	}
}
