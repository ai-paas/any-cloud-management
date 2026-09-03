package io.aipaas.cluster.agent.core;

/**
 * PodExec 결과의 표준 에러 코드. {@code ExecStatus.error_code} 필드에 enum name 그대로 들어감.
 *
 * <p>String literal 분산 사용을 막기 위한 single source of truth.
 *
 * <p>Wire format 은 enum name string — 다른 언어 클라이언트도 그대로 매칭하면 됨.
 */
public enum ExecErrorCode {

	/** 정상 종료 (exit code 0). */
	OK,

	/** Pod 명령이 non-zero exit code 로 종료. */
	EXIT_NONZERO,

	/** Exec 자체가 실패 (pod not found / container not running 등). */
	EXEC_FAILED,

	/** Allowlist 에 의해 namespace 거부됨. */
	NAMESPACE_DENIED,

	/** Allowlist 에 의해 EXEC_POD command 자체 거부. */
	PERMISSION_DENIED,

	/** Agent 가 stream 열기 전에 timeout (대상 cluster active session 없음 등). */
	AGENT_UNAVAILABLE,

	/** Agent ↔ backend stream 자체가 에러로 끊김. */
	AGENT_STREAM_ERROR,

	/** 사용자 입력 (namespace/pod 등) parsing 실패. */
	INVALID_PARAMS;

	public String wire() {
		return name();
	}
}
