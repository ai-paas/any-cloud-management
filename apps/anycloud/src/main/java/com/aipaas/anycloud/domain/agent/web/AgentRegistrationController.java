package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.agent.AgentProperties;
import com.aipaas.anycloud.domain.agent.api.response.AgentRegistrationTokenResponse;
import com.aipaas.anycloud.domain.agent.api.response.HelmInstallInstructionsResponse;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapService.ClusterNotRegisteredException;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/clusters/{clusterId}/agent-registration}.
 *
 * <p>Cluster Agent 를 클러스터 내부에 배포하기 위한 1회용 단기 JWT 발급. 사용자는 응답의
 * {@code registrationToken} 을 Helm value / kubectl Secret env 로 전달해 agent 를 기동.
 *
 * <p>JWT 자체에 cluster_id + scope=agent:register 박혀있고 jti 는 Redis SET NX 로 1회 사용 강제 —
 * 같은 token 으로 두 번 Register RPC 호출하면 두 번째는 PERMISSION_DENIED.
 */
@Slf4j
@RestController
@RequestMapping("/v1/clusters/{clusterId}/agent-registration")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cluster Agent (v1)", description = "Cluster Agent bootstrap — registration_token 발급")
public class AgentRegistrationController {

    private final AgentBootstrapService bootstrapService;
    /** 7개 @Value 분산 inject → 단일 AgentProperties record. */
    private final AgentProperties agentProperties;

    @PostMapping
    @Operation(
            summary = "Agent bootstrap JWT 발급",
            description = "Cluster Agent 가 backend 에 등록하기 위한 단기 (~10분) 1회용 JWT 발급. "
                    + "응답 token 을 agent 에 env / helm value 로 전달. 토큰은 jti 단위 1회 사용.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token 발급 성공"),
        @ApiResponse(responseCode = "404", description = "Cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<AgentRegistrationTokenResponse>> issue(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterId,
            @Parameter(
                            description = "Install mode (HELM_BOOTSTRAP / MANUAL / API_MANAGED). 기본 MANUAL.",
                            schema = @Schema(allowableValues = {"HELM_BOOTSTRAP", "MANUAL", "API_MANAGED"}))
                    @RequestParam(name = "installMode", required = false, defaultValue = "MANUAL")
                    String installMode) {
        try {
            IssuedToken issued = bootstrapService.issueRegistrationToken(clusterId, installMode);
            String normalizedMode = installMode.toUpperCase();

            // API_MANAGED 는 backend 가 자동 설치 — 사용자 helm install 명령 없음.
            // HELM_BOOTSTRAP / MANUAL 은 사용자가 직접 배포 → helm install snippet 제공.
            HelmInstallInstructionsResponse helmInstall =
                    "API_MANAGED".equals(normalizedMode) ? null : buildHelmInstructions(issued.token());

            AgentRegistrationTokenResponse dto = AgentRegistrationTokenResponse.builder()
                    .clusterId(clusterId)
                    .registrationToken(issued.token())
                    .expiresAt(issued.expiresAt())
                    .ttlSeconds(issued.ttlSeconds())
                    .controlPlaneEndpoint(agentProperties.grpc().publicEndpoint())
                    .installMode(normalizedMode)
                    .helmInstall(helmInstall)
                    .build();
            return ResponseEntity.ok(
                    ApiSuccessResponse.of(HttpStatus.OK.value(), "Agent registration token issued", dto));
        } catch (ClusterNotRegisteredException e) {
            log.warn("Agent registration token requested for missing cluster: {}", clusterId);
            throw new CustomException(e.getMessage(), ErrorCode.CLUSTER_NOT_FOUND);
        }
    }

    /**
     * 사용자가 helm install 로 cluster-agent 를 직접 배포할 때 사용할 values + 명령 snippet.
     * <p>
     * valuesYaml 은 token / backend.grpcAddr 만 들고 있음 — image 는 chart default 사용 (사용자가
     * --set image.repository / tag 로 override 가능). 사용자는 응답의 valuesYaml 을 stdin/파일로
     * 전달하면 됨.
     */
    private HelmInstallInstructionsResponse buildHelmInstructions(String token) {
        AgentProperties.Helm helm = agentProperties.helm();
        String namespace = agentProperties.manifest().namespace();
        String endpoint = agentProperties.grpc().publicEndpoint();

        String valuesYaml = String.format(
                """
				# anycloud cluster-agent helm install values — 응답에서 복사.
				bootstrap:
				  registrationToken: "%s"
				backend:
				  grpcAddr: "%s"
				""",
                token, endpoint);
        String installCommand = String.format(
                "helm install cluster-agent %s/%s --version %s --namespace %s --create-namespace -f -",
                helm.repoAlias(), helm.chartName(), helm.chartVersion(), namespace);
        return HelmInstallInstructionsResponse.builder()
                .repoAlias(helm.repoAlias())
                .repoUrl(helm.repoUrl())
                .chartName(helm.chartName())
                .chartVersion(helm.chartVersion())
                .namespace(namespace)
                .valuesYaml(valuesYaml)
                .installCommand(installCommand)
                .build();
    }
}
