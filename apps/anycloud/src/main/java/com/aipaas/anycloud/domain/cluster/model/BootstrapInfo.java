package com.aipaas.anycloud.domain.cluster.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cluster registration 응답에 포함되는 agent-led bootstrap 정보.
 *
 * <p>backend 가 cluster K8s API 를 직접 치지 않음 — token + URL/명령 노출만.
 * 사용자가 자신의 kubectl context 에서 직접 install (ArgoCD / Flux / OCM 표준 pull-based pattern).
 *
 * <p>토큰은 단기 (default 30분) — 그 안에 install 못 하면 분실 시 admin reinstall endpoint
 * ({@code POST /v1/clusters/{name}/agent/reinstall}) 로 재발급.
 *
 * @param token              Short-lived registration JWT — agent helm chart 의 bootstrap.registrationToken 값
 * @param expiresAt Token 만료 시각. 그 전에 helm install 실행 필요.
 * @param backendEndpoint    Agent 가 dial 할 backend gRPC endpoint (host:port).
 * @param manifestUrl        Ready-to-apply YAML — {@code curl ... | kubectl apply -f -} 가능.
 * @param helmInstallCommand 권장 helm install 명령 (one-line, copy/paste).
 * @param kubectlApplyCommand 대안 — manifest URL 을 curl + kubectl apply.
 */
@Schema(description = "Agent-led cluster bootstrap 정보 — cluster 등록 직후 한 번만 노출")
public record BootstrapInfo(
        @Schema(description = "Short-lived registration JWT", example = "eyJhbGciOi...") String token,
        @Schema(description = "Token 만료 시각 (ISO-8601)", example = "2026-06-08T00:30:00Z") String expiresAt,
        @Schema(description = "Agent 가 dial 할 backend gRPC endpoint", example = "anycloud.example.com:9090")
                String backendEndpoint,
        @Schema(description = "Ready-to-apply manifest URL", example = "/v1/clusters/orb-001/agent-manifest.yaml")
                String manifestUrl,
        @Schema(
                        description = "권장 helm install 명령 — kubectl context 에서 직접 실행",
                        example =
                                "helm install cluster-agent --namespace aipaas-system --create-namespace --set bootstrap.registrationToken=eyJ... --set backend.grpcAddr=anycloud:9090 oci://docker.io/aipaas/cluster-agent")
                String helmInstallCommand,
        @Schema(
                        description = "대안 — manifest URL 을 curl 로 받아 kubectl apply",
                        example =
                                "curl http://localhost:8888/v1/clusters/orb-001/agent-manifest.yaml | kubectl apply -f -")
                String kubectlApplyCommand) {}
