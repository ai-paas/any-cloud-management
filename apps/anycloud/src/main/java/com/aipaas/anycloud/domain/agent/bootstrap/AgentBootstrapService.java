package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.TokenHasher;
import io.aipaas.cluster.agent.v1.AgentIdentity;
import io.aipaas.cluster.agent.v1.ClusterIdentity;
import io.aipaas.cluster.agent.v1.NetworkInfo;
import java.time.LocalDateTime;

/**
 * cluster agent 등록과 identity token 교체입니다.
 *
 * <p>토큰이 둘입니다. {@link #issueRegistrationToken} 이 주는 10분짜리 JWT 는 등록 한 번에만 쓰고,
 * {@link #register} 가 돌려주는 60일짜리 identity_token 이 이후 모든 gRPC 호출의 자격증명입니다.
 * 후자는 cluster 의 K8s Secret 에 저장돼 pod 가 재시작해도 그대로 삽니다.
 *
 * <p>인증 수단은 이 bearer token 하나뿐입니다. mTLS 는 쓰지 않습니다.
 */
public interface AgentBootstrapService {

    /** installMode = HELM_BOOTSTRAP / MANUAL / API_MANAGED. cluster 미등록 시 {@link ClusterNotRegisteredException}. */
    IssuedToken issueRegistrationToken(String clusterId, String installMode);

    /**
     * registration JWT 를 검증하고 agent row 를 upsert 한 뒤 identity_token 을 발급합니다.
     *
     * <p>agent 는 이 토큰을 이후 모든 gRPC 호출의 Authorization 헤더에 싣습니다. 만료가 가까우면
     * {@link #rotateIdentityToken} 으로 바꿔야 합니다.
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
