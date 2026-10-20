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
 * Pulumi stack destroy 를 생략하고 DB row 만 삭제하는 admin endpoint.
 *
 * <p>이전 자격증명이나 다른 passphrase 로 암호화된 state 는 select 단계에서 실패해 일반 DELETE
 * 가 완료되지 않음. 해당 상황의 복구 수단.
 *
 * <p>CSP 자원과 RustFS 의 state file 은 잔존하고 backend 는 추적 불가. 동일 stackName 으로
 * cluster 재생성 시 잔존 state 와 충돌하므로 운영자가 자원과 state file 직접 삭제 필요.
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
     * RustFS 의 잔존 stack file 만 삭제. DB row 는 변경 없음.
     *
     * <p>force-delete 후 동일 stackName 으로 cluster 를 재생성할 때 사용.
     *
     * <p>CSP 자원은 삭제 대상 아님. state 를 먼저 삭제하면 backend 의 자원 추적 수단이 사라지므로
     * 콘솔이나 CLI 로 자원 정리 후 호출.
     */
    @DeleteMapping("/{stackName}/orphan-state")
    @Operation(
            summary = "Pulumi orphan stack file 정리 (RustFS 의 state 만)",
            description = "옛 cred 로 쓰여진 또는 destroy 실패로 잔존하는 stack file 을 backend 에서 제거. "
                    + "force-delete 의 자매: DB 는 손 안 대고 Pulumi state 만.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "stack file 정리 (혹은 이미 없음)"),
        @ApiResponse(responseCode = "500", description = "Pulumi command 실패: message 참고")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> cleanupOrphanState(
            @PathVariable @Size(max = 200) String stackName) {
        Map<String, Object> result = cleanupService.cleanupOrphanState(stackName);
        return ResponseEntity.ok(ApiSuccessResponse.of(200, "Orphan stack file removed", result));
    }
}
