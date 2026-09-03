package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.kube.KubeService;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import io.aipaas.cluster.agent.runtime.ResourceKindInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster 가 지원하는 API resource (kind) catalog 조회. UI 의 "resource kind picker" 데이터 소스.
 *
 * <p>cluster 마다 결과가 다름 — CRD 가 다양하기 때문 (cert-manager, ingress-nginx, custom CRD 등).
 * Agent 의 discovery API 를 통해 동적 enumerate — backend 가 미리 알 수 없는 kind 도 노출.
 *
 * <p>Agent 의 {@code LIST_RESOURCE_KINDS} command 결과를 정규화.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/clusters/{clusterName}")
@Validated
@Tag(name = "Cluster Resource Catalog (v1)", description = "Cluster 가 지원하는 API resource (kind) 동적 카탈로그")
public class ClusterResourceCatalogController {

    private final KubeService kubeService;

    @GetMapping("/resource-kinds")
    @Operation(
            summary = "Cluster 지원 kind 목록",
            description = "Agent 의 discovery API 결과를 정규화해 노출. plural / singular / kind / group / "
                    + "version / namespaced / shortNames 포함. CRD 자동 포함. cluster 마다 결과 다름.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "kind 목록 조회 성공 (group/plural 순 정렬)"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성 — 기본 12 kind 로 degrade 권장")
    })
    public ResponseEntity<ApiSuccessResponse<List<ResourceKindInfo>>> listResourceKinds(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        List<ResourceKindInfo> kinds = kubeService.listResourceKinds(clusterName);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Resource kinds loaded (" + kinds.size() + ")", kinds));
    }

    @GetMapping("/resolve")
    @Operation(
            summary = "Kind single-resolution",
            description = "사용자 입력 (단축이름 'pvc' / plural 'pods' / Kind 'Pod') 을 정규화. "
                    + "type-ahead / form validation 용. 실패 시 404 + suggestions (Levenshtein top-3).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정규화 성공"),
        @ApiResponse(
                responseCode = "404",
                description = "kind resolve 실패 — body 의 suggestions 배열 (Levenshtein 거리 ≤ 3) 활용"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성")
    })
    public ResponseEntity<ApiSuccessResponse<ResolvedResource>> resolveKind(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestParam("input") @NotBlank @Size(max = 128, message = "input must be 1..128 chars") String input) {
        ResolvedResource resolved = kubeService.resolveResource(clusterName, input);
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Resolved '" + input + "' → " + resolved.kind() + "/" + resolved.version(),
                resolved));
    }
}
