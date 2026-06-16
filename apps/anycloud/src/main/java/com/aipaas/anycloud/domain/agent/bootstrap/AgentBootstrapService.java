package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.aipaas.cluster.agent.identity.TokenHasher;
import io.aipaas.cluster.agent.v1.AgentIdentity;
import io.aipaas.cluster.agent.v1.ClusterIdentity;
import io.aipaas.cluster.agent.v1.NetworkInfo;
import java.time.LocalDateTime;

/**
 * Cluster agent 의 등록 / identity token rotation 책임.
 *
 * <p>대표 흐름:
 * <ol>
 *   <li>backend 가 {@link #issueRegistrationToken} 로 단기 JWT 발급 (10 분 TTL).</li>
 *   <li>agent 가 cluster 안에서 backend gRPC 로 {@link #register} 호출 → ClusterAgentEntity
 *       upsert + identity_token (32B opaque hex, 60일 TTL) 발급.</li>
 *   <li>identity_token 은 K8s Secret 에 영구 저장. pod 재시작 후에도 같은 token 으로 인증.</li>
 *   <li>만료 임박 시 {@link #rotateIdentityToken} 로 새 token 발급 후 기존 token 무효화.</li>
 * </ol>
 *
 * <p>mTLS 제거. Rancher 와 동일한 bearer-over-TLS 모델로 회귀.
 * 운영 단순성 / 외부 재사용성 우선. cert 인프라 (BackendCa, CSR, RenewCert) 모두 폐기.
 */
public interface AgentBootstrapService {

    /** installMode = HELM_BOOTSTRAP / MANUAL / API_MANAGED. cluster 미등록 시 {@link ClusterNotRegisteredException}. */
    IssuedToken issueRegistrationToken(String clusterId, String installMode);

    /**
     * JWT 검증 + (cluster_id, agent_instance_id) upsert + identity_token (32B hex) 발급.
     *
     * <p>인증 흐름은 단일 (bearer-only): 본 register 가 발급한 identity_token 을 agent 가 이후 모든
     * gRPC 호출의 Authorization header 에 부착. 60 일 TTL, 만료 임박 시 rotateIdentityToken.
     */
    RegistrationResult register(
            String registrationToken,
            ClusterIdentity clusterIdentity,
            AgentIdentity agentIdentity,
            NetworkInfo network);

    /**
     * Identity token rotation — 살아있는 token hash → 새 token 발급, DB 교체. revoked/expired 면 거부.
     *
     * @param requestingInstanceId 진단용 (HA 시 어느 instance 가 rotate 했는지 log).
     */
    RotationResult rotateIdentityToken(String currentTokenHash, String requestingInstanceId);

    /** hex — starter 의 표준 helper 위임. test 등 호출 측 호환을 위해 interface 에 유지. */
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
