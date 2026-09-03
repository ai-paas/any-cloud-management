package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService.UpgradeResult;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeOrchestrator;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunQueryService;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService.FleetPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fleet upgrade staggered rollout 관리 endpoint.
 *
 * <p> (preview + wave 지정):
 * <ul>
 *   <li>{@code GET /v1/fleet/upgrade/preview} — fleet 의 wave 분포 + version 분포 + per-cluster
 *       상세. 운영자가 upgrade plan 짜기 전에 현황 파악.</li>
 *   <li>{@code PATCH /v1/clusters/{name}/upgrade-wave} — 단일 cluster 의 wave 변경.</li>
 * </ul>
 *
 * <p> (다음 sprint): 실제 helm upgrade trigger ({@code POST /v1/fleet/upgrade} +
 * orchestrator + progress tracking).
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "Fleet Upgrade (v1)", description = "Staggered rollout — wave 기반 agent 신버전 배포 관리")
public class FleetUpgradeController {

    private final FleetUpgradeService fleetUpgradeService;
    private final AgentUpgradeService agentUpgradeService;
    private final FleetUpgradeOrchestrator orchestrator;
    private final FleetUpgradeRunQueryService runQueryService;

    @GetMapping("/fleet/upgrade/preview")
    @Operation(
            summary = "Fleet upgrade preview",
            description = "Fleet 전체의 wave 분포 + agent version 분포 + per-cluster 상세. "
                    + "운영자가 upgrade plan 짜기 전에 현황 점검용. CANARY → STAGING → GENERAL 순서로 정렬.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Preview 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<FleetPreview>> preview() {
        FleetPreview p = fleetUpgradeService.preview();
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Fleet upgrade preview", p));
    }

    @PatchMapping("/clusters/{clusterName}/upgrade-wave")
    @Operation(
            summary = "Cluster 의 upgrade wave 변경",
            description = "단일 cluster 를 CANARY / STAGING / GENERAL / PAUSED wave 로 지정. "
                    + "HA replica 가 여러 row 인 경우 모두 같은 wave 로 sync.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Wave 변경 성공"),
        @ApiResponse(responseCode = "404", description = "해당 cluster 의 agent row 없음")
    })
    public ResponseEntity<ApiSuccessResponse<Void>> setWave(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestBody @NotNull @jakarta.validation.Valid SetWaveRequest body) {
        fleetUpgradeService.setWave(clusterName, body.wave());
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Wave updated to " + body.wave() + " for cluster " + clusterName, null));
    }

    /** PATCH 요청 본문 — single field. */
    public record SetWaveRequest(@NotNull ClusterAgentUpgradeWave wave) {}

    @PostMapping("/clusters/{clusterName}/upgrade")
    @Operation(
            summary = "Cluster agent upgrade trigger",
            description = "단일 cluster 의 agent 를 target image 로 upgrade. Backend 가 최소 Deployment "
                    + "patch 를 agent path (APPLY_MANIFEST) 로 보내 K8s rolling update 트리거. "
                    + "trigger 직후 IN_PROGRESS 응답 — 진행 감지는 heartbeat 기반 (별도 monitor 가 "
                    + "SUCCEEDED/FAILED 전환). 같은 image 면 NO_OP.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Upgrade triggered or no-op"),
        @ApiResponse(responseCode = "404", description = "ACTIVE 한 agent 없음"),
        @ApiResponse(responseCode = "409", description = "이미 진행 중인 upgrade"),
        @ApiResponse(responseCode = "503", description = "Agent unavailable — manifest apply 실패")
    })
    public ResponseEntity<ApiSuccessResponse<UpgradeResult>> upgrade(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestBody @NotNull @jakarta.validation.Valid UpgradeRequest body) {
        UpgradeResult r = agentUpgradeService.upgradeCluster(clusterName, body.targetImage());
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Upgrade " + r.status(), r));
    }

    /** POST 본문 — target image 만. wave 는 cluster 의 upgrade_wave 컬럼에서 가져옴 (별도 patch). */
    public record UpgradeRequest(
            @NotBlank
                    @Size(max = 256)
                    @Pattern(regexp = "^[\\w./:-]+$", message = "Invalid image reference — alphanumeric + ._-:/ only")
                    String targetImage) {}

    // =========================================================================
    // Fleet-wide orchestration endpoints
    // =========================================================================

    @org.springframework.web.bind.annotation.PostMapping("/fleet/upgrade")
    @Operation(
            summary = "Fleet-wide upgrade submit",
            description = "여러 wave 의 cluster 를 wave 순서 + concurrency limit + auto-abort 로 "
                    + "순차 upgrade. PLANNED row 즉시 반환 — background scheduler 가 RUNNING 전환 후 진행.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Run accepted (PLANNED)"),
        @ApiResponse(responseCode = "400", description = "잘못된 wave / image / threshold")
    })
    public ResponseEntity<ApiSuccessResponse<FleetUpgradeRunEntity>> submitFleetUpgrade(
            @RequestBody @NotNull @jakarta.validation.Valid FleetUpgradeRequest body, HttpServletRequest request) {
        String createdBy = request.getRemoteUser();
        var run = orchestrator.submit(
                body.targetImage(),
                body.waves(),
                body.concurrency() == null ? 5 : body.concurrency(),
                body.failureThreshold() == null ? 20 : body.failureThreshold(),
                createdBy);
        return ResponseEntity.accepted()
                .body(ApiSuccessResponse.of(HttpStatus.ACCEPTED.value(), "Fleet upgrade run created", run));
    }

    @GetMapping("/fleet/upgrade/runs")
    @Operation(
            summary = "Fleet upgrade run history",
            description = "최근 20개 run (PLANNED / RUNNING / COMPLETED / ABORTED 모두). createdAt DESC.")
    public ResponseEntity<ApiSuccessResponse<List<com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRun>>>
            listRuns() {
        // Domain record 반환 — JPA proxy 없이 안전한 JSON serialize.
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Fleet upgrade runs", runQueryService.listRecentRunsDomain()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/fleet/upgrade/{runId}/abort")
    @Operation(
            summary = "Fleet upgrade abort",
            description = "운영자가 진행 중 run 을 강제 중단. ABORTED 로 전환 — 이미 IN_PROGRESS 인 "
                    + "cluster 는 그 자체로 SUCCEEDED/FAILED 까지 진행 (개별 K8s rolling update 는 못 멈춤).")
    public ResponseEntity<ApiSuccessResponse<FleetUpgradeRunEntity>> abortRun(
            @PathVariable @NotBlank @Pattern(regexp = "^[a-f0-9-]{36}$", message = "runId must be UUID") String runId,
            @RequestBody(required = false) AbortRequest body) {
        var run = orchestrator.abort(runId, body == null ? null : body.reason());
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Run aborted", run));
    }

    public record FleetUpgradeRequest(
            @NotBlank
                    @Size(max = 256)
                    @Pattern(regexp = "^[\\w./:-]+$", message = "Invalid image reference — alphanumeric + ._-:/ only")
                    String targetImage,
            @NotNull
                    @jakarta.validation.constraints.Size(
                            min = 1,
                            max = 3,
                            message = "waves: 1-3 entries (CANARY/STAGING/GENERAL)")
                    List<ClusterAgentUpgradeWave> waves,
            Integer concurrency,
            Integer failureThreshold) {}

    public record AbortRequest(String reason) {}
}
