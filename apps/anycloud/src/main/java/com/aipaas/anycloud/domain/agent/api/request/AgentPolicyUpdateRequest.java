package com.aipaas.anycloud.domain.agent.api.request;

import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Agent policy PUT / PATCH 요청 body. backend 가 본 payload 를 agent 의 ConfigMap 으로 push.
 *
 * @param allowedNamespaces      명시 namespace list. {@code ["*"]} 도 가능 (모든 ns 허용).
 * @param allowedCommands        RPC 종류 list (예: "LIST_PODS").
 * @param allowedCharts          Helm chart rule list (format: "repo/name:min-max" 또는
 * @param allowedExecNamespaces  PodExec 전용 namespace list.
 * @param resourcePolicy         resource_policy 구조 (옵션, null 이면 변경 안 함 — PATCH 의미).
 * @param force                  true 면 HIGH severity warning 도 무시하고 적용.
 */
@Schema(
        description = "Agent allowlist + resource_policy PUT / PATCH 요청. ConfigMap 으로 직접 push. "
                + "PATCH 에서 null 필드는 현재 값 유지.")
public record AgentPolicyUpdateRequest(
        @Schema(
                        description = "허용 namespace. 와일드카드 [\"*\"] 가능. PUT 필수, PATCH 옵션.",
                        example = "[\"monitoring\",\"app\"]",
                        nullable = true)
                List<String> allowedNamespaces,
        @Schema(
                        description = "허용 command 목록. PUT 필수, PATCH 옵션.",
                        example = "[\"LIST_PODS\",\"LIST_RESOURCES\"]",
                        nullable = true)
                List<String> allowedCommands,
        @Schema(
                        description = "허용 Helm chart rule (repo/name:min-max 또는 repo/*:min-max). PUT 필수, PATCH 옵션.",
                        example = "[\"prometheus-community/kube-prometheus-stack:45.0.0-65.0.0\"]",
                        nullable = true)
                List<String> allowedCharts,
        @Schema(description = "PodExec 허용 namespace. PATCH 시 null 이면 변경 안 함.", nullable = true)
                List<String> allowedExecNamespaces,
        @Schema(description = "resource_policy 구조. PATCH 시 null 이면 변경 안 함.", nullable = true)
                AgentPolicySnapshot.ResourcePolicy resourcePolicy,

        /**
         * HIGH severity warning 무시하고 강제 적용.
         *
         * <p>대표적인 HIGH 경고:
         * <ul>
         *   <li>{@code MISSING_MANAGEMENT_COMMANDS} — allowedCommands 에서
         *       APPLY_AGENT_CONFIG/GET_AGENT_CONFIG/ENSURE_AGENT_CONFIG_ANNOTATIONS 누락.
         *       force=true 적용 시 backend 가 다음 호출에서 PERMISSION_DENIED — 복구는
         *       {@code kubectl patch cm -n aipaas-system aipaas-agent-allowlist} 로 직접.
         *   <li>{@code MISSING_SECRETS_DENY} — secrets 가 deny 에서 빠진 RBAC 위험.</li>
         *   <li>{@code STRICT_EMPTY_ALLOW} — strict mode 인데 allow list 비어있음.</li>
         * </ul>
         *
         * <p>운영 권장: force=true 는 reset/긴급 대응 한정. 일반 변경은 권고 따라 명시 수정.
         */
        @Schema(
                        description = "true 면 HIGH severity warning 도 무시하고 적용 (긴급 대응용). "
                                + "MISSING_MANAGEMENT_COMMANDS 우회 시 다음 backend 호출에서 PERMISSION_DENIED — "
                                + "복구는 kubectl patch 만 가능.",
                        defaultValue = "false")
                boolean force) {}
