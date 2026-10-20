package com.aipaas.anycloud.domain.agent.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 사용자가 직접 helm install 로 cluster-agent 를 배포할 때 필요한 정보.
 * {@link AgentRegistrationTokenResponse#helmInstall()} 에 포함되어 응답.
 *
 * <p>chart 는 OCI registry 로만 publish. {@code helm repo add} 단계 없음.
 *
 * <p>사용 예 (사용자 shell):
 * <pre>{@code
 *   # 1) 응답의 valuesYaml 를 파일로 저장
 *   cat > /tmp/agent-values.yaml <<'EOF'
 *   <응답의 valuesYaml 내용 그대로>
 *   EOF
 *
 *   # 2) helm install
 *   helm install cluster-agent <chartRef> \
 *       --version <chartVersion> \
 *       --namespace <namespace> --create-namespace \
 *       -f /tmp/agent-values.yaml
 * }</pre>
 */
@Builder
@Schema(description = "사용자가 직접 helm install 시 필요한 values + 명령 snippet")
public record HelmInstallInstructionsResponse(
        @Schema(
                        description = "chart 의 OCI 좌표. helm install 의 chart 인자로 그대로 사용",
                        example = "oci://ghcr.io/ai-paas/charts/cluster-agent")
                String chartRef,
        @Schema(description = "chart 이름 (chartRef 의 마지막 segment)", example = "cluster-agent") String chartName,
        @Schema(description = "chart version", example = "0.1.0") String chartVersion,
        @Schema(description = "target namespace", example = "aipaas-system") String namespace,
        @Schema(
                        description = "values.yaml 내용: 그대로 파일에 붙여넣어 -f 로 전달",
                        example = "bootstrap:\n  registrationToken: \"eyJ...\"\nbackend:\n  grpcAddr: \"...\"\n")
                String valuesYaml,
        @Schema(
                        description = "복사-붙여넣기용 단일 라인 helm install 명령. valuesYaml 을 stdin 으로 전달.",
                        example =
                                "helm install cluster-agent oci://ghcr.io/ai-paas/charts/cluster-agent --version 0.1.0 -n aipaas-system --create-namespace -f -")
                String installCommand) {}
