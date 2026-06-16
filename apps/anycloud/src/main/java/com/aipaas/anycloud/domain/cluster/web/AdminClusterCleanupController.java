package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.cluster.admin.ClusterAdminCleanupService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint — Pulumi stack destroy 를 건너뛰는 force-delete + orphan Pulumi state 정리.
 *
 * <p>cred 분리 이전에 만들어진 stack 처럼 옛 cred / 다른 passphrase 로 암호화된 state 는
 * 새 환경에서 select 자체가 실패 → 일반 DELETE 는 무한 stuck. 본 endpoint 는 DB row 만 삭제하고
 * 외부 자원 (VPC, EC2, RustFS stack file) 은 운영자가 별도 정리.
 *
 * <p>⚠ 위험 — Pulumi state file 이 RustFS 에 잔존하지만 backend 는 모름. 같은 stackName 으로
 * 다시 cluster 생성 시 stale state 와 충돌 가능. 운영자가 RustFS 안의 해당 stack file 도 같이
 * 정리해야 안전.
 *
 * <p>컨트롤러는 Repository 를 직접 호출하지 않는다. 모든 로직은 {@link ClusterAdminCleanupService}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/clusters")
@RequiredArgsConstructor
@Validated
@Tag(name = "AdminClusterCleanup", description = "Cluster 강제 삭제 (Pulumi destroy 건너뜀)")
public class AdminClusterCleanupController {

    private final ClusterAdminCleanupService cleanupService;

    @DeleteMapping("/{clusterName}/force")
    @Operation(
            summary = "cluster force-delete (Pulumi destroy skip)",
            description = "옛 stack 잔존 / cred mismatch 로 일반 DELETE 가 실패하는 cluster 의 DB row 만 purge. "
                    + "Pulumi state file + 실제 CSP 자원은 운영자가 별도 정리해야 한다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "DB purge 완료"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> forceDelete(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        Map<String, Object> result = cleanupService.forceDelete(clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(200, "Force-delete done", result));
    }

    /**
     * RustFS 안의 orphan stack file 만 정리 — DB row 손 안 댐. force-delete 후 stale state file 이
     * 남아 같은 stackName 으로 새 cluster create 가 충돌하는 시나리오에 사용. {@code pulumi stack rm
     * --force --yes} 가 state resource 무시하고 file 삭제.
     *
     * <p>⚠ CSP 실 자원 (VPC/EC2 등) 정리 안 됨. 운영자가 콘솔/CLI 로 별도 정리해야 한다.
     */
    @DeleteMapping("/{stackName}/orphan-state")
    @Operation(
            summary = "Pulumi orphan stack file 정리 (RustFS 의 state 만)",
            description = "옛 cred 로 쓰여진 또는 destroy 실패로 잔존하는 stack file 을 backend 에서 제거. "
                    + "force-delete 의 자매 — DB 는 손 안 대고 Pulumi state 만.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "stack file 정리 (혹은 이미 없음)"),
        @ApiResponse(responseCode = "500", description = "Pulumi command 실패 — message 참고")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> cleanupOrphanState(
            @PathVariable @Size(max = 200) String stackName) {
        Map<String, Object> result = cleanupService.cleanupOrphanState(stackName);
        return ResponseEntity.ok(ApiSuccessResponse.of(200, "Orphan stack file removed", result));
    }
}
