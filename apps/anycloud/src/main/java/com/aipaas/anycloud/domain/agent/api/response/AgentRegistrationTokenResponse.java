package com.aipaas.anycloud.domain.agent.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * {@code POST /v1/clusters/{clusterId}/agent-registration} 응답.
 * Agent 에 전달할 JWT + 보조 정보 (api endpoint, helm values 등) 포함.
 */
@Builder
@Schema(description = "Cluster Agent bootstrap 정보 — registration_token + control plane endpoint")
public record AgentRegistrationTokenResponse(
        @Schema(description = "Cluster 의 플랫폼 내부 ID", example = "demo-aws-01") String clusterId,
        @Schema(
                        description = "단기 1회용 JWT (registration_token). Agent 에 env / helm value 로 전달.",
                        example = "eyJhbGciOiJIUzI1NiIs...")
                String registrationToken,
        @Schema(description = "JWT 만료 시각 (UTC, ISO-8601)", example = "2026-05-12T15:30:00Z") Instant expiresAt,
        @Schema(description = "JWT TTL (초). 권장 10분.", example = "600") long ttlSeconds,
        @Schema(
                        description = "Agent 가 bootstrap 시 접속할 backend gRPC endpoint",
                        example = "grpc.anycloud.example.com:9090")
                String controlPlaneEndpoint,
        @Schema(description = "Install mode (HELM_BOOTSTRAP / MANUAL / API_MANAGED)", example = "MANUAL")
                String installMode,
        @Schema(
                        description = "사용자가 직접 helm install 로 배포 시 사용할 values + 명령 snippet. "
                                + "installMode = HELM_BOOTSTRAP / MANUAL 일 때 채워짐. API_MANAGED (backend 자동 설치) 면 null.",
                        nullable = true)
                HelmInstallInstructionsResponse helmInstall) {}
