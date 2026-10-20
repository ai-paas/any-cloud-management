package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller.AgentInstallResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이미 등록된 cluster 에 agent 를 다시 배포합니다. chart 가 바뀌었거나 agent pod 가 복구되지 않을
 * 때 씁니다.
 *
 * <p>server-side apply 라 같은 manifest 를 여러 번 보내도 안전합니다.
 *
 * <p>호출할 때마다 registration token 이 새로 발급됩니다. 이전 token 은 따로 무효화하지 않고 10분
 * TTL 로 만료됩니다.
 *
 * <p>agent gRPC 가 끊긴 cluster 에는 적용할 수 없습니다. backend 가 cluster 자격증명을 보관하지
 * 않아 agent 를 거치지 않고 apply 할 수단이 없습니다. 그때는 응답이 알려주는 manifest 를 운영자가
 * 직접 적용해야 합니다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/clusters/{clusterName}/agent")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Admin (cluster-agent reinstall)",
        description = "이미 등록된 cluster 에 agent 재배포 (chart upgrade / image refresh / 복구)")
public class AdminAgentReinstallController {

    private final AgentApiManagedInstaller agentApiManagedInstaller;

    @PostMapping("/reinstall")
    @Operation(
            summary = "Cluster agent 재배포",
            description = "이미 등록된 cluster 에 최신 chart / image 로 agent 재배포. 새 registration "
                    + "token 발급 + manifest 의 server-side apply. agent pod 가 RollingUpdate 로 교체. "
                    + "기존 cluster_agent DB row 는 보존되며 agent 가 새 token 으로 re-register 시 "
                    + "identity_token + cert 가 갱신됨.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "재배포 trigger 성공 — 응답의 token 정보 + manifest 크기"),
        @ApiResponse(responseCode = "404", description = "Cluster 미등록"),
        @ApiResponse(responseCode = "503", description = "Cluster 도달 불가 (kubeconfig / network 점검)")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> reinstall(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        log.info("Admin-triggered agent reinstall cluster_id={}", clusterName);

        AgentInstallResult result = agentApiManagedInstaller.install(clusterName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterId", result.clusterId());
        body.put("registrationJti", result.registrationJti());
        body.put("tokenExpiresAt", result.tokenExpiresAt());
        body.put("manifestBytes", result.manifestBytes());
        body.put(
                "note",
                "Agent pod 가 RollingUpdate 로 새 deployment 로 교체됨. 새 token 으로 "
                        + "re-register 시 cluster_agent row 의 identity_token_hash + cert_serial 갱신. "
                        + "이전 agent pod 의 stream 은 PERMISSION_DENIED 받고 종료. "
                        + "rollout 확인: kubectl -n aipaas-system get pods -l app.kubernetes.io/name=cluster-agent");
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Agent reinstall triggered for " + clusterName, body));
    }
}
