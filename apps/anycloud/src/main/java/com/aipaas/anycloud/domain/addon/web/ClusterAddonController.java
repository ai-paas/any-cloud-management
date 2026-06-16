package com.aipaas.anycloud.domain.addon.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.addon.AddonService;
import com.aipaas.anycloud.domain.addon.api.response.AddonStatusResponse;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster addon CRUD + retry/backfill.
 *
 * <p>전제: ClusterAgentBootstrapServiceImpl 가 cluster ACTIVE 전환 시 자동으로 PENDING addon 들
 * enqueue. 본 controller 는 manual CRUD (frontend addon manager + admin retry).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/clusters/{clusterName}/addons")
@Validated
@Tag(name = "Cluster Addons (v1)", description = "Per-cluster addon CRUD + lifecycle")
public class ClusterAddonController {

    private static final String CLUSTER_REGEXP = ApiValidationConstants.K8S_NAME_PATTERN;
    private static final int CLUSTER_MAX = ApiValidationConstants.K8S_NAME_MAX;

    private final AddonService addonService;

    @GetMapping
    @Operation(summary = "Addon 목록", description = "해당 cluster 에 등록된 addon list + state.")
    public ResponseEntity<ApiSuccessResponse<List<AddonStatusResponse>>> list(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        List<AddonStatusResponse> addons = addonService.list(clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "addons", addons));
    }

    @GetMapping("/{addonId}")
    @Operation(summary = "Addon 상세")
    public ResponseEntity<ApiSuccessResponse<AddonStatusResponse>> get(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String addonId) {
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "addon", addonService.get(clusterName, addonId)));
    }

    @PostMapping
    @Operation(
            summary = "Addon 추가",
            description = "PENDING 상태로 row 생성. cluster ACTIVE 면 즉시 enqueue → background install. "
                    + "cluster 가 ACTIVE 아니면 ACTIVE 전환 시 ClusterAgentBootstrap listener 가 enqueue.")
    public ResponseEntity<ApiSuccessResponse<AddonStatusResponse>> add(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @Valid @RequestBody AddonSpec spec) {
        AddonStatusResponse created = addonService.create(clusterName, spec);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiSuccessResponse.of(HttpStatus.ACCEPTED.value(), "addon enqueued", created));
    }

    @DeleteMapping("/{addonId}")
    @Operation(summary = "Addon 제거", description = "DELETING state + uninstall queue. helm release 제거 완료 후 DELETED.")
    public ResponseEntity<ApiSuccessResponse<OperationView>> remove(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String addonId) {
        OperationEntity op = addonService.delete(clusterName, addonId);
        return ResponseEntity.accepted()
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(),
                        "addon uninstall enqueued",
                        new OperationView(
                                op.getId(), op.getType().name(), op.getState().name())));
    }

    @PostMapping("/{addonId}/retry")
    @Operation(summary = "Addon 재시도", description = "FAILED state 에서만 가능. ENQUEUED 로 전환 + install queue.")
    public ResponseEntity<ApiSuccessResponse<OperationView>> retry(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String addonId) {
        OperationEntity op = addonService.retry(clusterName, addonId);
        return ResponseEntity.accepted()
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(),
                        "addon retry enqueued",
                        new OperationView(
                                op.getId(), op.getType().name(), op.getState().name())));
    }

    // 콜론 custom-method 경로를 colon-free 서브패스로 통일. 특히 collection-level ":enqueue" 는
    // leading-slash 가 없어 Boot3 PathPatternParser 가 클래스 패턴과 결합 시 "/addons:enqueue" 로
    // 매칭하지 못해 404(NoResourceFoundException) 가 났다. 서브패스는 항상 매칭되고 proxy/gateway
    // 에서도 안전.
    @PostMapping("/enqueue")
    @Operation(
            summary = "Backfill — 모든 PENDING/FAILED enqueue",
            description = "ACTIVE 인 cluster 에 신규 addon 추가 직후 또는 운영 중 일괄 enqueue.")
    public ResponseEntity<ApiSuccessResponse<Integer>> enqueueAll(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        int count = addonService.reenqueueAllForCluster(clusterName);
        return ResponseEntity.accepted()
                .body(ApiSuccessResponse.of(HttpStatus.ACCEPTED.value(), "enqueued " + count + " addon(s)", count));
    }

    /**
     * — Addon 의 현재 active operation 식별자 단축 lookup.
     *
     * <p>frontend 가 addon row 의 lastOperationId 를 GET 한 뒤 SSE 구독하는 2-step 을 1-step 으로
     * 단축. response 의 {@code operationId} 를 가지고
     * {@code GET /v1/operations/{operationId}/events} 로 SSE 구독.
     *
     * <p>302 redirect 가 아닌 명시 lookup — SseEmitter 가 controller 간 forward 불편 + frontend 가
     * EventSource 직접 build 하므로 id 만 제공이 깔끔.
     */
    @GetMapping("/{addonId}/operation")
    @Operation(
            summary = "Addon 의 latest operation 식별자",
            description = "lastOperationId 가 null 이면 404. SSE 구독은 /v1/operations/{id}/events 로.")
    public ResponseEntity<ApiSuccessResponse<OperationView>> latestOperation(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String addonId) {
        AddonStatusResponse addon = addonService.get(clusterName, addonId);
        String opId = addon.lastOperationId();
        if (opId == null) {
            throw new jakarta.persistence.EntityNotFoundException("No operation associated with addon " + addonId
                    + " (state=" + addon.state() + " — still PENDING?)");
        }
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "latest operation",
                new OperationView(opId, "INSTALL_ADDON", addon.state().name())));
    }

    /** Minimal projection — frontend SSE subscribe 용 operation id + type/state. */
    public record OperationView(String id, String type, String state) {}
}
