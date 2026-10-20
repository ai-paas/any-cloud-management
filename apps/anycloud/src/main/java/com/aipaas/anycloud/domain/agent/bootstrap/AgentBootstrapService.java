package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.TokenHasher;
import io.aipaas.cluster.agent.v1.AgentIdentity;
import io.aipaas.cluster.agent.v1.ClusterIdentity;
import io.aipaas.cluster.agent.v1.NetworkInfo;
import java.time.LocalDateTime;

/**
 * cluster agent 등록과 identity token 교체.
 *
 * <p>토큰 2종. {@link #issueRegistrationToken} 이 발급하는 10분 JWT 는 등록 1회 전용,
 * {@link #register} 가 반환하는 60일 identity_token 이 이후 모든 gRPC 호출의 자격증명.
 * 후자는 cluster 의 K8s Secret 에 저장돼 pod 재시작 후에도 유지.
 *
 * <p>인증 수단은 bearer token 단일. mTLS 미사용.
 */
public interface AgentBootstrapService {

    /** installMode = HELM_BOOTSTRAP / MANUAL / API_MANAGED. cluster 미등록 시 {@link ClusterNotRegisteredException}. */
    IssuedToken issueRegistrationToken(String clusterId, String installMode);

    /**
     * registration JWT 검증, agent row upsert, identity_token 발급.
     *
     * <p>agent 는 이 토큰을 이후 모든 gRPC 호출의 Authorization 헤더에 첨부. 만료 임박 시
     * {@link #rotateIdentityToken} 으로 교체.
     */
    RegistrationResult register(
            String registrationToken,
            ClusterIdentity clusterIdentity,
            AgentIdentity agentIdentity,
            NetworkInfo network);

    /**
     * Identity token rotation: 살아있는 token hash → 새 token 발급, DB 교체. revoked/expired 면 거부.
     *
     * @param requestingInstanceId 진단용 (HA 시 어느 instance 가 rotate 했는지 log).
     */
    RotationResult rotateIdentityToken(String currentTokenHash, String requestingInstanceId);

    /** hex: starter 의 표준 helper 위임. test 등 호출 측 호환을 위해 interface 에 유지. */
    static String sha256Hex(String value) {
        return TokenHasher.sha256Hex(value);
    }

    // ============= Nested types (test / external caller 가 import) =============

    record RegistrationResult(
            String clusterId, String agentIdentityToken, LocalDateTime expiresAt, ClusterAgentStatus status) {}

    record RotationResult(String newToken, LocalDateTime expiresAt) {}

    class ClusterNotRegisteredException extends RuntimeException {
        public ClusterNotRegisteredException(String message) {
            super(message);
        }
    }

    class RotationDeniedException extends RuntimeException {
        public RotationDeniedException(String message) {
            super(message);
        }
    }
}
