package com.aipaas.anycloud.domain.kube.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.kube.KindResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin — {@link KindResolver} kind metadata cache 강제 flush.
 *
 * <p>일반 운영에서는 TTL 30분 + addon install hook 으로 충분하나, 다음과 같은 edge case 에서
 * 즉시 invalidate 가 필요해 별도 endpoint 제공:
 * <ul>
 *   <li>cluster 운영자가 backend addon-orchestration 외 채널 (kubectl, ArgoCD 등) 로 CRD 직접 등록</li>
 *   <li>operator chart upgrade 가 새 GVR 도입 — install hook 트리거 없음</li>
 *   <li>buggy / outdated cache 의 sanity flush</li>
 * </ul>
 *
 * <p>endpoint:
 * <ul>
 *   <li>{@code POST /v1/admin/clusters/{c}/kind-cache/flush} — 단일 cluster cache flush</li>
 *   <li>{@code POST /v1/admin/kind-cache/flush} — 전체 cluster cache flush (boot-time / global drift 복구)</li>
 * </ul>
 *
 * <p>Cache 가 schema 만 보유 (실 resource data 는 항상 fresh) — flush 안전, blast radius 는
 * 다음 호출 1회의 agent RESOLVE_RESOURCE 추가뿐.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin (kind cache)", description = "K8s kind metadata 캐시 운영자 제어")
public class AdminKindCacheController {

    private final KindResolver kindResolver;

    @PostMapping("/clusters/{clusterName}/kind-cache/flush")
    @Operation(
            summary = "단일 cluster 의 kind metadata cache flush",
            description =
                    "RESOLVE_RESOURCE 결과 cache 를 즉시 무효화. 다음 호출부터 agent 재조회. " + "resource data 는 캐시 대상 아님 — 동작 변경 없음.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> flushOne(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        log.info("AdminKindCacheController: flush cluster={}", clusterName);
        kindResolver.invalidate(clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Kind cache flushed", Map.of("clusterName", clusterName, "scope", "single")));
    }

    @PostMapping("/kind-cache/flush")
    @Operation(
            summary = "모든 cluster 의 kind metadata cache flush (전역)",
            description = "Backend 전체 KindResolver cache 무효화. 사용 빈도 낮음 — boot-time / sanity 용.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> flushAll() {
        log.warn("AdminKindCacheController: flush ALL clusters — global");
        kindResolver.invalidateAll();
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Kind cache flushed (all clusters)", Map.of("scope", "all")));
    }
}
