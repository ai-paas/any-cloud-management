package io.aipaas.cluster.agent.runtime;

/** Agent 경유 K8s ops 실패 — caller 가 fabric8 fallback 또는 에러 매핑. */
public class KubeRoutingException extends RuntimeException {

	public KubeRoutingException(String message) {
		super(message);
	}

	public KubeRoutingException(String message, Throwable cause) {
		super(message, cause);
	}
}
