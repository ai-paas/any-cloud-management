package com.aipaas.anycloud.domain.audit.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.audit.AuditLogResponse;
import com.aipaas.anycloud.domain.audit.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감사 로그 조회 API. mutation HTTP 요청 (POST/PUT/PATCH/DELETE) 의 자동 기록을 시간/리소스/액션/
 * principal 별로 검색.
 * <p>
 * layering 위반 해소 — controller 는 더 이상 repository 를 직접 import 하지 않고 service 만
 * 의존. 향후 audit policy (마스킹, RBAC, multi-tenancy) 가 service 계층에서 일원화 가능.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/audit-logs")
@Tag(name = "Audit Logs (v1)", description = "운영 감사 로그 조회")
@Validated
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(
            summary = "감사 로그 검색",
            description = "시간 윈도우 + (선택) resourceType/resourceId/action/principal 필터. "
                    + "최신 순. since/until 미지정 시 최근 1000 건 한도 검색.")
    public ResponseEntity<ApiSuccessResponse<PagedData<AuditLogResponse>>> search(
            @Parameter(description = "시작 시각 ISO 8601 (포함)") @RequestParam(required = false) LocalDateTime since,
            @Parameter(description = "종료 시각 ISO 8601 (미포함)") @RequestParam(required = false) LocalDateTime until,
            @Parameter(description = "리소스 타입", example = "cluster")
                    @RequestParam(name = "resource-type", required = false)
                    @Pattern(regexp = "^[A-Za-z0-9]{1,64}$")
                    String resourceType,
            @Parameter(description = "리소스 식별자", example = "demo-aws-01")
                    @RequestParam(name = "resource-id", required = false)
                    @Size(max = 128)
                    String resourceId,
            @Parameter(description = "표준 action 명", example = "cluster.createVmCluster")
                    @RequestParam(required = false)
                    @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$")
                    String action,
            @Parameter(description = "수행 주체 (gateway 가 forward 한 X-User-Id)")
                    @RequestParam(required = false)
                    @Size(max = 128)
                    String principal,
            @Parameter(description = "page size (1..500, default 100)")
                    @RequestParam(required = false, defaultValue = "100")
                    @Min(1)
                    @Max(500)
                    int limit) {
        List<AuditLogResponse> body = auditLogService.search(
                since, until, resourceType, resourceId, action, principal, PageRequest.of(0, limit));
        return new ResponseEntity<>(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Audit logs loaded", PagedData.of(body)),
                new HttpHeaders(),
                HttpStatus.OK);
    }
}
