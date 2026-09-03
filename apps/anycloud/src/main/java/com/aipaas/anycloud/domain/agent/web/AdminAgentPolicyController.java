package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyAuditService;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyDiffCalculator;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyDiffCalculator.PolicyDiff;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyMergeService;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator.PolicyWarning;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator.Severity;
import com.aipaas.anycloud.domain.audit.AuditEntry;
import com.aipaas.anycloud.domain.audit.AuditLogger;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint — agent 의 적용된 policy snapshot 조회 + 일관성 검증 + 변경 (PUT/PATCH).
 *
 * <p><b>설계</b>: backend 에는 정책 DB 저장 안 함. ConfigMap (cluster 안의
 * {@code aipaas-agent-allowlist}) 이 single source of truth. PUT/PATCH 는 새 snapshot 을 agent 에
 * push 해 ConfigMap 을 update — agent 의 기존 watch 가 자동 reload. audit 만 {@code audit_log} 에.
 *
 * <p>Helm upgrade 시 ConfigMap 보존: chart 의 ConfigMap 에 {@code helm.sh/resource-policy: keep}
 * annotation — chart upgrade 가 PUT 변경분을 덮어쓰지 않음.
 *
 * <p><b>PUT vs PATCH</b>:
 * <ul>
 *   <li>PUT — 전체 교체. 3개 필수 list (allowedNamespaces / allowedCommands / allowedCharts) 누락 시 400.</li>
 *   <li>PATCH — 부분 갱신. null 필드는 현재 snapshot 값 유지. backend 가 GET → merge → PUT.</li>
 * </ul>
 *
 * <p><b>책임 분리</b> (refactor): controller 는 routing + response building 만. merge /
 * diff / 직렬화 logic 은 각각 {@link AgentPolicyMergeService}, {@link AgentPolicyDiffCalculator}
 * 로 추출. fleet audit 은 {@link AgentPolicyAuditService}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin")
@Validated
@RequiredArgsConstructor
@Tag(name = "Admin (agent policy)", description = "Cluster-agent policy snapshot + 일관성 검증 + 변경")
public class AdminAgentPolicyController {

    private final KubeResourceService kubeResourceService;
    private final AgentPolicyValidator validator;
    private final AuditLogger auditLogger;
    private final ClusterService clusterService;
    /** fleet audit 의 parallel fetch logic 추출. */
    private final AgentPolicyAuditService policyAuditService;
    /** refactor — RFC 7396 / legacy PATCH merge 책임 분리. */
    private final AgentPolicyMergeService mergeService;
    /** refactor — diff 계산 + dry-run snapshot + agent param 직렬화 분리. */
    private final AgentPolicyDiffCalculator diffCalculator;

    // =================== Preview ===================

    @GetMapping("/agent/policy/preview")
    @Operation(
            summary = "Agent policy snapshot + warnings",
            description = "Agent 의 in-memory allowlist + resource_policy 를 조회 후 backend 가 추가 검증.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot + warnings 반환"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> preview(
            @RequestParam("cluster")
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        AgentPolicySnapshot snapshot;
        try {
            snapshot = kubeResourceService.getAgentConfig(clusterName);
        } catch (KubeRoutingException e) {
            return agentUnavailable(e);
        }
        List<PolicyWarning> warnings = validator.validate(snapshot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", snapshot);
        body.put("warnings", warnings);
        body.put("highestSeverity", diffCalculator.highestSeverity(warnings));
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Policy snapshot + " + warnings.size() + " warning(s)", body));
    }

    // =================== zz — Fleet-wide audit ===================

    @GetMapping("/agent/policy/audit")
    @Operation(
            summary = "Fleet-wide policy audit",
            description = "모든 등록 cluster 의 agent policy snapshot + validator 결과 집계. "
                    + "HIGH severity 우선 정렬 — 운영자가 즉시 문제 cluster 식별. "
                    + "agent unreachable cluster 는 UNREACHABLE 표시.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Fleet audit 결과")})
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> fleetAudit() {
        // 140 LOC 의 fleet-wide audit logic 은 AgentPolicyAuditService 로 추출.
        Map<String, Object> body;
        try {
            body = policyAuditService.runFleetAudit();
        } catch (Exception e) {
            log.error("Failed to run fleet audit: {}", e.getMessage());
            //  에러는 success-envelope 아닌 공통 ErrorResponse 로 — GlobalExceptionHandler 위임.
            throw new com.aipaas.anycloud.common.error.exception.CustomException(
                    "Fleet audit failed: " + e.getMessage(),
                    com.aipaas.anycloud.common.error.enums.ErrorCode.INTERNAL_SERVER_ERROR);
        }
        Object totalClusters = body.get("totalClusters");
        Object byHigh = body.get("bySeverity") instanceof Map<?, ?> m ? m.get("HIGH") : 0;
        Object durationMs = body.get("durationMs");
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Fleet audit — " + totalClusters + " clusters, " + byHigh + " with HIGH severity warning(s), "
                        + durationMs + "ms",
                body));
    }

    // =================== PUT — 전체 교체 ===================

    @PutMapping("/clusters/{clusterName}/agent-policy")
    @Operation(
            summary = "Agent policy 전체 교체 (PUT)",
            description = "전체 allowlist + resource_policy 를 새로 교체. 3개 list 필수 (null 거부). "
                    + "ww: 응답에 이전 snapshot 과 변경 diff 포함.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정책 적용 성공"),
        @ApiResponse(responseCode = "400", description = "필수 list 누락"),
        @ApiResponse(responseCode = "422", description = "HIGH severity warning — force=true 필요"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> putUpdate(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @Valid @RequestBody AgentPolicyUpdateRequest req) {
        // PUT 의 필수 검증 — 3개 list 가 null 이면 400 (controller 내 explicit, DTO 의 @NotNull 제거됨)
        List<String> missing = new ArrayList<>();
        if (req.allowedNamespaces() == null) missing.add("allowedNamespaces");
        if (req.allowedCommands() == null) missing.add("allowedCommands");
        if (req.allowedCharts() == null) missing.add("allowedCharts");
        if (!missing.isEmpty()) {
            return badRequest("PUT 은 필수 list 누락 불가: " + missing + ". 부분 갱신은 PATCH 사용.");
        }
        return applyChange(clusterName, req, "PUT");
    }

    // =================== PATCH — 부분 갱신 ===================

    @PatchMapping(value = "/clusters/{clusterName}/agent-policy", consumes = "application/json")
    @Operation(
            summary = "Agent policy 부분 갱신 (PATCH, legacy)",
            description = "null 필드 = 현재 값 유지 (omit 과 동일). "
                    + "ww: 응답에 diff 포함. "
                    + "CCC: RFC 7396 표준 따르려면 Content-Type: application/merge-patch+json 사용.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "부분 갱신 성공"),
        @ApiResponse(responseCode = "422", description = "HIGH severity warning — force=true 필요"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> patchUpdate(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @Valid @RequestBody AgentPolicyUpdateRequest req) {
        // PATCH — 현재 snapshot fetch 후 null 필드 fill-in.
        AgentPolicySnapshot current;
        try {
            current = kubeResourceService.getAgentConfig(clusterName);
        } catch (KubeRoutingException e) {
            return agentUnavailable(e);
        }
        AgentPolicyUpdateRequest merged = mergeService.mergeWithCurrent(req, current);
        return applyChange(clusterName, merged, "PATCH");
    }

    // =================== CCC — RFC 7396 JSON Merge Patch ===================

    /**
     * RFC 7396 표준 JSON Merge Patch.
     *
     * <p>Content-Type 으로 application/json (legacy) vs application/merge-patch+json (RFC 7396) 구분.
     *
     * <p>의미론 차이:
     * <ul>
     *   <li>field 생략 (absent) → 두 모드 모두 현재 값 유지</li>
     *   <li>field 명시 + 값 있음 → 두 모드 모두 새 값 사용</li>
     *   <li>field 명시 + 값 null → <b>legacy: 현재 값 유지</b>, <b>RFC 7396: 값 비우기</b></li>
     * </ul>
     *
     * <p>RFC 7396 의 "비우기" 의미:
     * <ul>
     *   <li>list 필드 (allowedNamespaces 등) → 빈 list {@code []}</li>
     *   <li>resourcePolicy → null (legacy 동작 — 정책 비활성)</li>
     * </ul>
     *
     * <p>Jackson 의 일반 deserialize 는 absent / null 구분 불가 → JsonNode 로 raw parse 후
     * {@code has(field)} 로 명시 여부 확인.
     */
    @PatchMapping(value = "/clusters/{clusterName}/agent-policy", consumes = "application/merge-patch+json")
    @Operation(
            summary = "Agent policy RFC 7396 merge patch",
            description = "RFC 7396 표준 — field 명시 + null 값 = 그 필드 비우기 (legacy 의 'null=keep' 와 반대). "
                    + "Content-Type: application/merge-patch+json 사용.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "부분 갱신 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 JSON 또는 schema"),
        @ApiResponse(responseCode = "422", description = "HIGH severity warning — force=true 필요"),
        @ApiResponse(responseCode = "503", description = "Cluster agent 비활성")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> mergePatchUpdate(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestBody JsonNode body) {

        if (body == null || !body.isObject()) {
            return badRequest("Body must be a JSON object");
        }

        AgentPolicySnapshot current;
        try {
            current = kubeResourceService.getAgentConfig(clusterName);
        } catch (KubeRoutingException e) {
            return agentUnavailable(e);
        }

        // RFC 7396 — 각 필드의 has(field) + isNull() 로 의미 결정.
        AgentPolicyUpdateRequest merged;
        try {
            merged = mergeService.applyMergePatch(body, current);
        } catch (Exception e) {
            return badRequest("Failed to parse merge patch body: " + e.getMessage());
        }
        return applyChange(clusterName, merged, "MERGE_PATCH");
    }

    // =================== 공통 apply 흐름 ===================

    /**
     * PUT / PATCH / MERGE_PATCH 의 공통 흐름 — before snapshot fetch → validate → apply → diff → audit.
     *
     * <p>HIGH severity warning 이 있고 {@code force=false} 면 422 반환.
     */
    private ResponseEntity<ApiSuccessResponse<Map<String, Object>>> applyChange(
            String clusterName, AgentPolicyUpdateRequest req, String httpMethod) {

        // 1) ww — 이전 snapshot fetch (diff 계산용). PATCH 는 이미 위에서 fetch 했지만 PUT 도 필요.
        AgentPolicySnapshot before;
        try {
            before = kubeResourceService.getAgentConfig(clusterName);
        } catch (KubeRoutingException e) {
            log.warn("Could not fetch before-snapshot for diff: {}", e.getMessage());
            before = null; // diff 일부 정보 누락 가능 — apply 자체는 진행
        }

        // 2) request → 가상 snapshot 구성 (검증용)
        AgentPolicySnapshot dryRun = diffCalculator.buildDryRunSnapshot(req);
        List<PolicyWarning> warnings = validator.validate(dryRun);
        String highestSeverity = diffCalculator.highestSeverity(warnings);

        // 3) HIGH severity + force=false → 422
        if ("HIGH".equals(highestSeverity) && !req.force()) {
            return highSeverityRejection(clusterName, httpMethod, warnings, highestSeverity);
        }

        // 4) 직렬화
        String namespacesJson = diffCalculator.toJsonArray(req.allowedNamespaces());
        String commandsJson = diffCalculator.toJsonArray(req.allowedCommands());
        String chartsJson = diffCalculator.toJsonArray(req.allowedCharts());
        String execNamespacesJson = diffCalculator.toJsonArray(req.allowedExecNamespaces());
        String resourcePolicyYaml = diffCalculator.toYamlOrEmpty(req.resourcePolicy());

        // 5) agent 호출
        String newResourceVersion;
        try {
            newResourceVersion = kubeResourceService.applyAgentConfig(
                    clusterName, namespacesJson, commandsJson, chartsJson, execNamespacesJson, resourcePolicyYaml);
        } catch (KubeRoutingException e) {
            log.error("Failed to apply agent policy: cluster={}, cause={}", clusterName, e.getMessage());
            return agentUnavailable(e);
        }

        // 6) ww — diff 계산 (before vs new)
        PolicyDiff diff = diffCalculator.computeDiff(before, dryRun);

        // 7) audit
        auditLogger.record(AuditEntry.builder()
                .action("agentPolicy." + httpMethod.toLowerCase())
                .resourceType("agentPolicy")
                .resourceId(clusterName)
                .statusCode(HttpStatus.OK.value())
                .requestSummary(diff.toAuditSummary(newResourceVersion, req.force()))
                .build());

        log.info(
                "Agent policy {} applied: cluster={}, resourceVersion={}, force={}, diff={}",
                httpMethod,
                clusterName,
                newResourceVersion,
                req.force(),
                diff.toAuditSummary("", false));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterName", clusterName);
        body.put("method", httpMethod);
        body.put("appliedResourceVersion", newResourceVersion);
        body.put("diff", diff.toMap()); // ww — 응답에 diff 포함
        body.put("warnings", warnings);
        body.put("highestSeverity", highestSeverity);
        body.put(
                "note",
                "Agent 의 ConfigMap watch 가 변경 감지 후 reload — 즉시 적용. " + "확인: GET /v1/admin/agent/policy/preview?cluster="
                        + clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Agent policy applied (resourceVersion=" + newResourceVersion + ")", body));
    }

    // =================== 응답 매핑 ===================

    /** HIGH severity warning + {@code force=false} → 422 응답 + warning list. */
    private ResponseEntity<ApiSuccessResponse<Map<String, Object>>> highSeverityRejection(
            String clusterName, String httpMethod, List<PolicyWarning> warnings, String highestSeverity) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        err.put("code", "POLICY_VALIDATION_FAILED");
        err.put("message", "Policy 에 HIGH severity warning 존재 — force=true 로 강제 적용 가능");
        err.put("warnings", warnings);
        err.put("highestSeverity", highestSeverity);
        log.warn(
                "Agent policy {} rejected: cluster={}, HIGH warnings={}",
                httpMethod,
                clusterName,
                warnings.stream().filter(w -> w.severity() == Severity.HIGH).count());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiSuccessResponse.of(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Policy validation failed", err));
    }

    /** Agent unreachable 시 503 + {@code AGENT_UNAVAILABLE} code. */
    private static ResponseEntity<ApiSuccessResponse<Map<String, Object>>> agentUnavailable(KubeRoutingException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        err.put("code", "AGENT_UNAVAILABLE");
        err.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiSuccessResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage(), err));
    }

    /** 400 + {@code INVALID_INPUT_VALUE} code. */
    private static ResponseEntity<ApiSuccessResponse<Map<String, Object>>> badRequest(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("status", HttpStatus.BAD_REQUEST.value());
        err.put("code", "INVALID_INPUT_VALUE");
        err.put("message", message);
        return ResponseEntity.badRequest().body(ApiSuccessResponse.of(HttpStatus.BAD_REQUEST.value(), message, err));
    }
}
