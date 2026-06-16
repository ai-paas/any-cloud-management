package io.aipaas.cluster.agent.terminal;

import io.aipaas.cluster.agent.v1.ExecRequest;
import io.aipaas.cluster.agent.v1.ExecStatus;
import io.aipaas.cluster.agent.v1.TerminalSize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import io.aipaas.cluster.agent.core.ExecErrorCode;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.terminal.ExecSessionRegistry.PendingExecSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Pod exec WebSocket terminal endpoint.
 *
 * <p><b>URL</b>: {@code ws(s)://<host>/v1/clusters/{clusterName}/pods/{namespace}/{pod}/exec}.
 *
 * <p><b>Query params</b> (optional):
 * <ul>
 *   <li>{@code container} — 다중 container pod 의 특정 container. 비어있으면 첫 컨테이너.</li>
 *   <li>{@code command} — 실행할 명령. comma 분리 (예: {@code /bin/bash,-l}). default: {@code /bin/sh}.</li>
 *   <li>{@code tty} — {@code true}/{@code false}. default true (interactive shell).</li>
 *   <li>{@code cols} / {@code rows} — 초기 PTY 크기. default 80x24.</li>
 * </ul>
 *
 * <p><b>Wire protocol</b> (간단 / kubernetes-dashboard 호환):
 * <ul>
 *   <li><b>Client → Server</b>: binary frame = stdin bytes. text frame = {@code
 *       {"type":"resize","cols":N,"rows":N}} 또는 {@code {"type":"stdin","data":"<base64>"}} JSON.</li>
 *   <li><b>Server → Client</b>: binary frame = stdout/stderr (TTY 모드면 머지됨). 마지막 text frame =
 *       {@code {"type":"end","exitCode":N,"errorCode":"...","message":"..."}} 으로 종료 신호.</li>
 * </ul>
 *
 * <p>MDC: 모든 로그에 {@code cluster}, {@code session} 컨텍스트 자동 주입.
 */
@Slf4j
@RequiredArgsConstructor
public class PodExecWebSocketHandler extends AbstractWebSocketHandler {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ExecSessionRegistry execSessionRegistry;
	private final AgentSessionRegistry agentSessionRegistry;
	private final List<AgentLifecycleListener> listeners;
	private final Duration bindTimeout;

	/** WebSocket session id → bridge. close 시 정리. */
	private final Map<String, BoundExec> bridgesByWs = new ConcurrentHashMap<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
		ExecParams params;
		try {
			params = ExecParams.fromSession(wsSession);
		} catch (IllegalArgumentException e) {
			sendEnd(wsSession, -1, ExecErrorCode.INVALID_PARAMS.wire(), e.getMessage());
			wsSession.close(CloseStatus.BAD_DATA);
			return;
		}

		PendingExecSession pending = execSessionRegistry.createPending(bindTimeout);
		ExecBridge bridge = pending.bridge();
		String clusterName = params.clusterName();

		try (MDC.MDCCloseable c1 = MDC.putCloseable("cluster", clusterName);
				MDC.MDCCloseable c2 = MDC.putCloseable("session", pending.sessionId())) {

			bridge.setCallbacks(new ExecBridge.Callbacks() {
				@Override
				public void onStdout(byte[] data) {
					safeSendBinary(wsSession, data);
				}
				@Override
				public void onStderr(byte[] data) {
					safeSendBinary(wsSession, data);
				}
				@Override
				public void onEnd(ExecStatus status) {
					try {
						sendEnd(wsSession, status.getExitCode(), status.getErrorCode(), status.getMessage());
					} catch (Exception ignored) {
					}
					try {
						wsSession.close(CloseStatus.NORMAL);
					} catch (IOException ignored) {
					}
					execSessionRegistry.remove(pending.sessionId());
					bridgesByWs.remove(wsSession.getId());
					notifyListeners(l -> l.onExecSessionEnded(clusterName, pending.sessionId(), status));
				}
			});

			ExecRequest request = ExecRequest.newBuilder()
					.setSessionId(pending.sessionId())
					.setNamespace(params.namespace())
					.setPod(params.pod())
					.setContainer(params.container() == null ? "" : params.container())
					.addAllCommand(params.command())
					.setTty(params.tty())
					.setStdin(true)
					.setInitialSize(TerminalSize.newBuilder()
							.setCols(params.cols())
							.setRows(params.rows())
							.build())
					.build();

			boolean pushed = agentSessionRegistry.openExecSession(clusterName, pending.sessionId(), request);
			if (!pushed) {
				sendEnd(wsSession, -1, ExecErrorCode.AGENT_UNAVAILABLE.wire(),
						"no active agent session for cluster " + clusterName);
				wsSession.close(CloseStatus.SERVICE_RESTARTED);
				execSessionRegistry.remove(pending.sessionId());
				return;
			}

			bridgesByWs.put(wsSession.getId(), new BoundExec(bridge, clusterName));
			notifyListeners(l -> l.onExecSessionStarted(clusterName, pending.sessionId(), request));
			log.info("PodExec WS opened ws={}", wsSession.getId());
		}
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
		BoundExec bound = bridgesByWs.get(wsSession.getId());
		if (bound == null) {
			return;
		}
		byte[] bytes = new byte[message.getPayload().remaining()];
		message.getPayload().get(bytes);
		bound.bridge.sendStdinFromUser(bytes);
	}

	@Override
	protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
		BoundExec bound = bridgesByWs.get(wsSession.getId());
		if (bound == null) {
			return;
		}
		try {
			JsonNode node = MAPPER.readTree(message.getPayload());
			String type = node.path("type").asText();
			if ("resize".equals(type)) {
				int cols = node.path("cols").asInt(80);
				int rows = node.path("rows").asInt(24);
				bound.bridge.sendResizeFromUser(cols, rows);
			} else if ("stdin".equals(type)) {
				String b64 = node.path("data").asText("");
				if (!b64.isEmpty()) {
					bound.bridge.sendStdinFromUser(java.util.Base64.getDecoder().decode(b64));
				}
			}
		} catch (Exception e) {
			log.debug("PodExec WS: malformed text frame: {}", e.toString());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
		BoundExec bound = bridgesByWs.remove(wsSession.getId());
		if (bound != null) {
			bound.bridge.closeFromUser();
			execSessionRegistry.remove(bound.bridge.getSessionId());
		}
		log.info("PodExec WS closed ws={} status={}", wsSession.getId(), status);
	}

	private void safeSendBinary(WebSocketSession ws, byte[] data) {
		try {
			if (ws.isOpen()) {
				synchronized (ws) {
					ws.sendMessage(new BinaryMessage(data));
				}
			}
		} catch (Exception e) {
			log.debug("PodExec WS send failed ws={}: {}", ws.getId(), e.toString());
		}
	}

	private void sendEnd(WebSocketSession ws, int exitCode, String errorCode, String message) throws IOException {
		if (!ws.isOpen()) {
			return;
		}
		Map<String, Object> payload = Map.of(
				"type", "end",
				"exitCode", exitCode,
				"errorCode", errorCode == null ? "" : errorCode,
				"message", message == null ? "" : message);
		synchronized (ws) {
			ws.sendMessage(new TextMessage(MAPPER.writeValueAsString(payload)));
		}
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

	private record BoundExec(ExecBridge bridge, String clusterName) {}

	/**
	 * URI path 와 query 에서 exec 파라미터 추출. URI pattern:
	 * {@code /v1/clusters/{cluster}/pods/{ns}/{pod}/exec}.
	 */
	private record ExecParams(
			String clusterName,
			String namespace,
			String pod,
			String container,
			List<String> command,
			boolean tty,
			int cols,
			int rows) {

		static ExecParams fromSession(WebSocketSession s) {
			String path = s.getUri() == null ? "" : s.getUri().getPath();
			String[] parts = path.split("/");
			// /v1/clusters/{cluster}/pods/{ns}/{pod}/exec → ["", "v1", "clusters", "{cluster}", "pods", "{ns}", "{pod}", "exec"]
			if (parts.length < 8 || !"clusters".equals(parts[2]) || !"pods".equals(parts[4])
					|| !"exec".equals(parts[7])) {
				throw new IllegalArgumentException("invalid exec URI: " + path);
			}
			String cluster = parts[3];
			String ns = parts[5];
			String pod = parts[6];

			Map<String, List<String>> query = parseQuery(s.getUri() == null ? "" : s.getUri().getQuery());
			String container = first(query.get("container"));
			String commandRaw = first(query.get("command"));
			List<String> command;
			if (commandRaw == null || commandRaw.isBlank()) {
				command = List.of("/bin/sh");
			} else {
				command = List.of(commandRaw.split(","));
			}
			boolean tty = !"false".equalsIgnoreCase(first(query.get("tty")));
			int cols = parseIntOr(first(query.get("cols")), 80);
			int rows = parseIntOr(first(query.get("rows")), 24);
			return new ExecParams(cluster, ns, pod, container, command, tty, cols, rows);
		}

		private static Map<String, List<String>> parseQuery(String q) {
			if (q == null || q.isBlank()) {
				return Collections.emptyMap();
			}
			Map<String, List<String>> out = new java.util.HashMap<>();
			for (String pair : q.split("&")) {
				int eq = pair.indexOf('=');
				String k = eq < 0 ? pair : pair.substring(0, eq);
				String v = eq < 0
						? ""
						: java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				out.computeIfAbsent(k, kk -> new java.util.ArrayList<>()).add(v);
			}
			return out;
		}

		private static String first(List<String> list) {
			return list == null || list.isEmpty() ? null : list.get(0);
		}

		private static int parseIntOr(String s, int fallback) {
			if (s == null || s.isBlank()) {
				return fallback;
			}
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
	}
}
