package com.aipaas.anycloud.domain.addon.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.addon.AddonCatalog;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Addon catalog REST.
 *
 * <p>Frontend 가 cluster 생성 dialog 에서 checkbox 렌더 시 GET /v1/addons 로 catalog 받아 사용.
 * 각 entry 의 id 를 {@code AddonSpec.catalogId} 로 보내면 backend 가 chart spec resolve.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@Tag(name = "Addon Catalog (v1)", description = "Catalog of installable cluster addons")
public class AddonCatalogController {

    private final AddonCatalog catalog;

    @GetMapping("/addons")
    @Operation(
            summary = "Addon catalog 목록",
            description = "frontend 가 cluster 생성 시 checkbox 렌더에 사용. 각 entry 의 id 가 " + "AddonSpec.catalogId 의 값.")
    public ResponseEntity<ApiSuccessResponse<List<AddonCatalogProperties.Entry>>> list() {
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "addon catalog", catalog.list()));
    }
}
