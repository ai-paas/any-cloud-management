package com.aipaas.anycloud.domain.agent.api.request;

import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * agent policy PUT, PATCH 의 body. backend 가 이 값을 agent 의 ConfigMap 으로 전달.
 *
 * <p>PATCH 의 null 필드는 현재 값 유지. PUT 은 전체 교체이므로 목록 3종 모두 필요.
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
         * HIGH 경고 무시 후 적용. 긴급 대응 전용.
         *
         * <p>{@code MISSING_MANAGEMENT_COMMANDS} 무시 시 backend 의 ConfigMap 수정 불가.
         * 이 경우 {@code kubectl patch cm -n aipaas-system aipaas-agent-allowlist} 로 직접 복구.
         */
        @Schema(
                        description = "true 면 HIGH severity warning 도 무시하고 적용 (긴급 대응용). "
                                + "MISSING_MANAGEMENT_COMMANDS 우회 시 다음 backend 호출에서 PERMISSION_DENIED: "
                                + "복구는 kubectl patch 만 가능.",
                        defaultValue = "false")
                boolean force) {}
