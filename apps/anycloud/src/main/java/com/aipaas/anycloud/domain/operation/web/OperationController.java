package com.aipaas.anycloud.domain.operation.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전역 Operation 조회. 클러스터 하위 operation 은 {@code /v1/clusters/{name}/operations}
 * 도 참조 (동일 자원 다른 진입점).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/operations")
@Tag(name = "Operations", description = "Long-Running Operation 추적 (전역)")
@Validated
public class OperationController {

    private final OperationService operationService;

    @GetMapping
    @Operation(summary = "operation 검색")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PagedData<OperationResponse>"),
        @ApiResponse(responseCode = "400", description = "필터/페이지 인자 형식 위반")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<OperationResponse>>> search(
            @RequestParam(required = false) OperationState state,
            @RequestParam(required = false) OperationType type,
            @RequestParam(name = "resourceType", required = false) @Pattern(regexp = "^[A-Za-z0-9]{1,48}$")
                    String resourceType,
            @RequestParam(name = "resourceId", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    String resourceId,
            @Parameter(description = "페이지 크기 (1..500)")
                    @RequestParam(name = "pageSize", required = false, defaultValue = "50")
                    @Min(1)
                    @Max(500)
                    int pageSize) {
        List<OperationResponse> items =
                operationService.search(state, type, resourceType, resourceId, pageSize).stream()
                        .map(OperationResponse::from)
                        .toList();
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Operations loaded", PagedData.of(items)));
    }

    @GetMapping("/{operationId}")
    @Operation(summary = "단일 operation 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OperationResponse"),
        @ApiResponse(responseCode = "400", description = "operation id 형식 위반 또는 not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> getOne(
            @PathVariable @Pattern(regexp = ApiValidationConstants.OPERATION_ID_PATTERN) String operationId) {
        var op = operationService
                .findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Operation not found: " + operationId));
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Operation loaded", OperationResponse.from(op)));
    }

    @PostMapping("/{operationId}/cancel")
    @Operation(
            summary = "operation 취소 (best-effort)",
            description = "현재 RUNNING 인 operation 의 cancel 요청. 실제 백엔드 작업 중단은 best-effort.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "취소 요청 수락 — state=CANCELLED 로 갱신"),
        @ApiResponse(responseCode = "400", description = "operation id 형식 위반 또는 not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> cancel(
            @PathVariable @Pattern(regexp = ApiValidationConstants.OPERATION_ID_PATTERN) String operationId) {
        var op = operationService.cancel(operationId);
        return ResponseEntity.accepted()
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "Operation cancellation requested", OperationResponse.from(op)));
    }
}
