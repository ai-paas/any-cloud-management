package io.aipaas.cluster.agent.observability.core;

/**
 * Observability 명령 실패 (agent 응답이 OK 아닌 경우).
 *
 * <p>Caller (REST controller) 가 errorCode 로 분기:
 * <ul>
 *   <li>{@code NO_ACTIVE_AGENT} — cluster 가 카탈로그엔 있지만 agent stream 끊김</li>
 *   <li>{@code TIMEOUT}        — agent 응답 timeout</li>
 *   <li>{@code GRAFANA_NOT_EXPOSED}, {@code PROM_QUERY_FAILED} 등 — agent 가 보낸 raw code</li>
 * </ul>
 */
public class ObservabilityException extends RuntimeException {

	private final String errorCode;

	public ObservabilityException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ObservabilityException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
