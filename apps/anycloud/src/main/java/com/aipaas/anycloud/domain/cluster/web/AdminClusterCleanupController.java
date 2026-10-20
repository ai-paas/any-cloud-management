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
 * Pulumi stack destroy 를 건너뛰고 DB row 만 지우는 admin endpoint 입니다.
 *
 * <p>옛 자격증명이나 다른 passphrase 로 암호화된 state 는 select 부터 실패해 일반 DELETE 가 끝나지
 * 않습니다. 그때 쓰는 탈출구입니다.
 *
 * <p>CSP 자원과 RustFS 의 state file 은 그대로 남고 backend 는 더 이상 그 존재를 모릅니다. 같은
 * stackName 으로 cluster 를 다시 만들면 남은 state 와 충돌하므로, 운영자가 자원과 state file 을
 * 직접 지워야 합니다.
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
     * RustFS 에 남은 stack file 만 지웁니다. DB row 는 건드리지 않습니다.
     *
     * <p>force-delete 뒤 같은 stackName 으로 새 cluster 를 만들려 할 때 씁니다.
     *
     * <p>CSP 자원은 지우지 않습니다. state 를 먼저 지우면 backend 가 자원을 추적할 방법이 없어지니,
     * 콘솔이나 CLI 로 자원을 정리한 뒤 호출하세요.
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
