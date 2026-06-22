package io.aipaas.cluster.provisioning.api.exception;

/**
 * Pulumi 실행(up / preview / destroy)이 외부 시스템 실패로 끝났을 때 던지는 starter 예외.
 *
 * <p>입력 오류(4xx)나 상태 충돌이 아니라, Pulumi CLI / CSP API 등 <em>upstream</em> 실패를 나타발신.
 * host backend 는 이 예외를 자신의 에러 정책(예: anycloud → HTTP 502 UPSTREAM_FAILED)으로 매핑.
 * starter 는 host 의 에러 타입(CustomException 등)에 의존하지 않는다 — 경계 역전 방지.
 */
public class ProvisioningExecutionException extends RuntimeException {

	public ProvisioningExecutionException(String message) {
		super(message);
	}

	public ProvisioningExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
