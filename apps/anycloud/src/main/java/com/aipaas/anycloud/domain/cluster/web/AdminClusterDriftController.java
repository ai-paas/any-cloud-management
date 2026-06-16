package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.provisioning.admin.VmClusterDriftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint — Pulumi state drift 감지 + 복구.
 *
 * <p>Drift = Pulumi state 와 실제 CSP 자원의 불일치. 운영자가 콘솔에서 VM 을 직접 지우거나
 * 변경하면 발생 — 이후 scale / destroy 가 stale state 기준으로 동작해 실패하거나 잘못된 자원을
 * 만진다.
 *
 * <ul>
 *   <li>GET {@code /drift} — {@code pulumi preview --refresh --json} 으로 read-only 감지.</li>
 *   <li>POST {@code /refresh-state} — {@code pulumi refresh --yes} 로 state 를 실제 CSP
 *       상태와 동기화. state 만 갱신 — CSP 자원 자체는 건드리지 않는다.</li>
 * </ul>
 *
 * <p>컨트롤러는 VmClusterRepository 를 직접 호출하지 않는다. 모든 도메인 로직은
 * {@link VmClusterDriftService}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/clusters")
@RequiredArgsConstructor
@Validated
@Tag(name = "AdminClusterDrift", description = "Pulumi state drift 감지 + refresh 복구")
public class AdminClusterDriftController {

    private final VmClusterDriftService driftService;

    @GetMapping("/{clusterName}/drift")
    @Operation(
            summary = "Pulumi state drift 감지 (read-only)",
            description = "`pulumi preview --refresh --json` — 실제 CSP 상태를 읽어 state/프로그램과 비교. "
                    + "same 외의 op 가 있으면 drift 또는 미적용 변경. CSP API 호출로 수십 초 걸릴 수 있다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "감지 완료 — drifted + changeSummary"),
        @ApiResponse(responseCode = "404", description = "cluster not found"),
        @ApiResponse(responseCode = "500", description = "Pulumi command 실패 — stderr 참고")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> detectDrift(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        Map<String, Object> result = driftService.detectDrift(clusterName);
        boolean drifted = Boolean.TRUE.equals(result.get("drifted"));
        return ResponseEntity.ok(ApiSuccessResponse.of(200, drifted ? "Drift detected" : "No drift", result));
    }

    @PostMapping("/{clusterName}/refresh-state")
    @Operation(
            summary = "Pulumi state 를 실제 CSP 상태와 동기화 (pulumi refresh)",
            description = "운영자가 콘솔에서 자원을 직접 변경/삭제해 state 가 낡았을 때 사용. "
                    + "state file 만 갱신 — CSP 자원 자체는 만들지도 지우지도 않는다. "
                    + "이후 drift 재확인 또는 scale/destroy 재시도 권장.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "refresh 완료"),
        @ApiResponse(responseCode = "404", description = "cluster not found"),
        @ApiResponse(responseCode = "500", description = "Pulumi command 실패 — stderr 참고")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> refreshState(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        Map<String, Object> result = driftService.refreshState(clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(200, "State refreshed", result));
    }
}
