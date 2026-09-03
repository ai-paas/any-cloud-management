package com.aipaas.anycloud.grpc;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.ClusterNotRegisteredException;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RegistrationResult;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RotationDeniedException;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.RotationResult;
import io.aipaas.cluster.agent.grpc.AuthMetadataInterceptor;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.RegistrationTokenInvalidException;
import io.aipaas.cluster.agent.identity.TokenHasher;
import io.aipaas.cluster.agent.v1.AgentBootstrapGrpc;
import io.aipaas.cluster.agent.v1.ClusterStatus;
import io.aipaas.cluster.agent.v1.RegisterRequest;
import io.aipaas.cluster.agent.v1.RegisterResponse;
import io.aipaas.cluster.agent.v1.RotateRequest;
import io.aipaas.cluster.agent.v1.RotateResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC server impl for {@link AgentBootstrapGrpc.AgentBootstrapImplBase}.
 *
 * <p>mTLS 제거. RenewCert RPC handler 삭제, register 도 csr_pem 인자 제거.
 * Bearer-only 인증.
 *
 * <p>인증 방식: Authorization metadata header 의 Bearer JWT (registration_token).
 * gRPC interceptor 가 따로 검증하지 않고 본 handler 안에서 직접 검증 — bootstrap 채널은 단일
 * RPC 라 별도 interceptor chain 가 과한 추상화.
 *
 * <p>실패 매핑:
 * <ul>
 *   <li>{@link RegistrationTokenInvalidException} → {@link Status#PERMISSION_DENIED}</li>
 *   <li>{@link ClusterNotRegisteredException} → {@link Status#NOT_FOUND}</li>
 *   <li>기타 → {@link Status#INTERNAL}</li>
 * </ul>
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class AgentBootstrapEndpoint extends AgentBootstrapGrpc.AgentBootstrapImplBase {

    private static final Metadata.Key<String> AUTH_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentBootstrapService bootstrapService;

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        String token = extractBearerToken();
        if (token == null) {
            log.warn("Register rejected: missing Authorization header");
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Authorization header missing")
                    .asRuntimeException());
            return;
        }

        try {
            RegistrationResult result =
                    bootstrapService.register(token, request.getCluster(), request.getAgent(), request.getNetwork());

            RegisterResponse resp = RegisterResponse.newBuilder()
                    .setClusterId(result.clusterId())
                    .setAgentIdentityToken(result.agentIdentityToken())
                    .setExpiresAt(result.expiresAt().toInstant(ZoneOffset.UTC).toString())
                    // synchronous register → 응답 시점에 이미 REGISTERED.
                    // Saga 전환 시 PENDING 가능.
                    .setClusterStatus(toProtoStatus(result.status().name()))
                    .build();

            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (RegistrationTokenInvalidException e) {
            log.warn("Register rejected: invalid token — {}", e.getMessage());
            responseObserver.onError(
                    Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (ClusterNotRegisteredException e) {
            log.warn("Register rejected: cluster not found — {}", e.getMessage());
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("Register internal error", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Identity token rotation. Bearer 의 현재 token 으로 인증 → 새 token 발급 + DB 의
     * token hash + expires_at 교체. 응답으로 새 plaintext token + 만료 시각.
     */
    @Override
    public void rotateIdentityToken(RotateRequest request, StreamObserver<RotateResponse> responseObserver) {
        String currentToken = extractBearerToken();
        if (currentToken == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Authorization header missing")
                    .asRuntimeException());
            return;
        }
        String currentHash = TokenHasher.sha256Hex(currentToken);
        try {
            RotationResult result = bootstrapService.rotateIdentityToken(currentHash, request.getAgentInstanceId());
            RotateResponse resp = RotateResponse.newBuilder()
                    .setNewIdentityToken(result.newToken())
                    .setExpiresAt(result.expiresAt().toInstant(ZoneOffset.UTC).toString())
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (RotationDeniedException e) {
            log.warn("Identity rotation denied: {}", e.getMessage());
            responseObserver.onError(
                    Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("Identity rotation internal error", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Authorization metadata 추출. {@link AuthMetadataInterceptor} 가 BEARER_TOKEN_CONTEXT 에
     * 넣어주는 게 정석. bootstrap 은 단순화 — net.devh starter 의 GrpcServerInterceptor
     * 추가 없이 ServerCallStreamObserver attribute 로 우회 불가능하므로 본 메서드에서 직접 추출.
     */
    private String extractBearerToken() {
        // gRPC Java 의 metadata 는 ThreadLocal 이 아닌 ServerCall.attributes 로 노출됨.
        // net.devh starter 의 GrpcServerInterceptor 없이 가장 간단한 방법: io.grpc.Context 의
        // CONTEXT_AUTHORIZATION key 사용. 그러나 별도 interceptor 등록 없이는 비어 있음.
        // 는 GrpcServerInterceptorAdapter 로 metadata 를 Context 로 옮기는 방식.
        String token = AuthMetadataInterceptor.AUTHORIZATION_CONTEXT.get();
        if (token == null || !token.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return token.substring(BEARER_PREFIX.length()).trim();
    }

    private static ClusterStatus toProtoStatus(String status) {
        return switch (status) {
            case "REGISTERING" -> ClusterStatus.CLUSTER_STATUS_PENDING;
            case "REGISTERED", "ACTIVE" -> ClusterStatus.CLUSTER_STATUS_ACTIVE;
            case "DEGRADED" -> ClusterStatus.CLUSTER_STATUS_DEGRADED;
            case "FAILED", "REVOKED" -> ClusterStatus.CLUSTER_STATUS_FAILED;
            default -> ClusterStatus.CLUSTER_STATUS_UNSPECIFIED;
        };
    }
}
