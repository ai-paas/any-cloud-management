package com.aipaas.anycloud.domain.agent.model;

/**
 * Cluster Agent 의 lifecycle 상태.
 *
 * <pre>
 *   REGISTERING → REGISTERED → ACTIVE
 *                    ↓
 *                 DEGRADED (API_MANAGED 실패 / 일부 기능 제한)
 *                    ↓
 *                 FAILED (등록 실패 / 토큰 만료)
 *                    ↓
 *                 REVOKED (수동/자동 회수)
 * </pre>
 *
 * <p>현재 register flow 는 synchronous — gRPC handler 가 DB transaction 까지 완료 후 응답.
 * REGISTERING 은 향후 비동기 saga 전환 시 (DB 저장은 됐으나 후속 처리 대기) 의 transient 상태 자리.
 */
public enum ClusterAgentStatus {

    /** 등록 진행 중 — DB row 는 있으나 후속 처리 대기 (비동기 saga 전환 시 사용). */
    REGISTERING,

    /** Backend Consumer 처리 완료 + agent_identity_token 발급. Agent 가 stream 시작 직전. */
    REGISTERED,

    /** Runtime gRPC stream 정상 — heartbeat/명령 처리 중. */
    ACTIVE,

    /** API_MANAGED 실패 등 비정상/제한 동작. 일부 기능만 가능. */
    DEGRADED,

    /** 등록 실패 (token 만료, cluster_id 충돌). 롤백/정리 필요. */
    FAILED,

    /** Token revoke 됨 (수동 / cluster 삭제 / 보안 사고). Bearer 인증 거부. */
    REVOKED
}
