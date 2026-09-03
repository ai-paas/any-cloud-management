package io.aipaas.cluster.agent.core;

import io.aipaas.cluster.agent.v1.AgentEvent;
import io.aipaas.cluster.agent.v1.ExecRequest;
import io.aipaas.cluster.agent.v1.ExecStatus;
import io.aipaas.cluster.agent.v1.Heartbeat;

/**
 * Cluster Agent 의 lifecycle 이벤트 훅 SPI.
 *
 * <p>호스트 애플리케이션이 본 인터페이스를 구현하면 starter 가 자동 호출. 여러 listener 등록 가능 — Spring
 * 이 모든 bean 을 수집해서 차례로 invoke.
 *
 * <p>모든 default 메서드는 no-op — 필요한 훅만 override 하면 됨. <b>예외 던지지 말 것</b> — listener
 * 실패가 stream 자체를 끊으면 안 되므로 starter 측에서 try/catch 로 swallow.
 *
 * <p>대표 활용 예:
 * <ul>
 *   <li>Anycloud 의 {@code KubeconfigLifecycleService} 가 {@link #onStreamConnected} 훅에서 cleanup</li>
 *   <li>RabbitMQ / HTTP webhook publisher 가 {@link #onAgentEvent} 훅에서 외부 forward</li>
 *   <li>모니터링: {@link #onExecSessionStarted} / {@link #onExecSessionEnded} 로 audit log 작성</li>
 * </ul>
 */
public interface AgentLifecycleListener {

	/** Bootstrap.Register RPC 통과 후 신규 identity 저장 완료 시 호출. */
	default void onAgentRegistered(AgentIdentity agent) {}

	/** Runtime.Stream RPC open + 인증 통과 후 ACTIVE 전환 시 호출. */
	default void onStreamConnected(AgentIdentity agent) {}

	/** Runtime.Stream RPC close (정상/에러 무관) 시 호출. */
	default void onStreamDisconnected(String clusterName, String agentInstanceId) {}

	/** Agent → backend heartbeat 도착 시 호출. */
	default void onHeartbeat(String clusterName, Heartbeat heartbeat) {}

	/** Agent 가 unsolicited event 보낼 때 호출 (예: pod.crashed, addon.installed). */
	default void onAgentEvent(String clusterName, AgentEvent event) {}

	/** PodExec WebSocket 세션 시작 시 호출 (OpenExecSession push 직전). */
	default void onExecSessionStarted(String clusterName, String sessionId, ExecRequest request) {}

	/** PodExec 세션 종료 시 호출 (정상 종료 / agent 단절 / timeout 무관). */
	default void onExecSessionEnded(String clusterName, String sessionId, ExecStatus status) {}
}
