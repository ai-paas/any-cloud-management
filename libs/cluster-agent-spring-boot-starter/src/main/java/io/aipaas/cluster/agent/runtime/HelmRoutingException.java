package io.aipaas.cluster.agent.runtime;

/**
 * Agent 경유 Helm ops 실패 — caller (ChartServiceImpl) 가 helm CLI + kubeconfig fallback 또는
 * 에러 매핑 결정. {@link KubeRoutingException} 의 helm 쌍.
 */
public class HelmRoutingException extends RuntimeException {

	public HelmRoutingException(String message) {
		super(message);
	}

	public HelmRoutingException(String message, Throwable cause) {
		super(message, cause);
	}
}
