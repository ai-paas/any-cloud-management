package io.aipaas.cluster.agent.core;

/**
 * Cluster agent 의 lifecycle 상태.
 *
 * <pre>
 *   REGISTERING → REGISTERED → ACTIVE
 *                    ↓
 *                 DEGRADED (일부 기능 제한)
 *                    ↓
 *                 FAILED (등록 실패 / 토큰 만료)
 *                    ↓
 *                 REVOKED (수동/자동 회수)
 * </pre>
 *
 * <p>본 enum 은 starter 가 노출하는 표준 상태값. 호스트 애플리케이션이 DB 컬럼/JSON 표현 등에 어떤 형태로
 * 저장하든, {@link AgentIdentityStore} adapter 에서 본 enum 으로 매핑해서 반환하면 된다.
 */
public enum AgentStatus {

	/** Bootstrap RPC 진행 중 또는 비동기 register 처리 대기. */
	REGISTERING,

	/** Bootstrap 완료 + agent_identity_token 발급. Runtime stream 아직 미연결. */
	REGISTERED,

	/** Runtime stream 연결 후 정상 heartbeat 수신 중. */
	ACTIVE,

	/** 일부 기능 제한 (예: API_MANAGED 설치 실패, 부분 권한). 기본 기능은 동작. */
	DEGRADED,

	/** 등록 실패 / 권한 거부 / 인증 토큰 폐기 등 복구 불가 상태. */
	FAILED,

	/** Agent 의 등록 자격 자체가 revoke 됨 (운영자 강제 해제). */
	REVOKED
}
