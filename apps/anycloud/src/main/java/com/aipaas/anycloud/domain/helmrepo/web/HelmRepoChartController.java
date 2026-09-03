package com.aipaas.anycloud.domain.helmrepo.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.chart.ChartMetadataService;
import com.aipaas.anycloud.domain.chart.api.response.ChartDetailResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartListResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReadmeResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartValuesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Helm repository 의 chart catalog. chart 는 repo 의 자식 — URL 가 계층을 표현.
 * <ul>
 *   <li>GET /v1/helm-repos/{repo}/charts                — chart list</li>
 *   <li>GET /v1/helm-repos/{repo}/charts/{chart}        — detail</li>
 *   <li>GET /v1/helm-repos/{repo}/charts/{chart}/values</li>
 *   <li>GET /v1/helm-repos/{repo}/charts/{chart}/readme</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/helm-repos/{repoName}/charts")
@Tag(name = "Helm Charts (v1)", description = "Helm repo 의 chart catalog")
@Validated
public class HelmRepoChartController {

    private static final String NAME_PATTERN = "^[A-Za-z0-9_.-]{1,64}$";
    private static final String VERSION_PATTERN = "^[A-Za-z0-9._+-]{1,32}$";

    private final ChartMetadataService chartService;

    @GetMapping
    @Operation(summary = "chart list")
    public ResponseEntity<ApiSuccessResponse<ChartListResponse>> list(
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String repoName) {
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Charts loaded", chartService.getChartList(repoName)));
    }

    @GetMapping("/{chartName}")
    @Operation(summary = "chart detail")
    public ResponseEntity<ApiSuccessResponse<ChartDetailResponse>> detail(
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String repoName,
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String chartName,
            @RequestParam(required = false) @Pattern(regexp = VERSION_PATTERN) String version) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Chart detail loaded",
                chartService.getChartDetail(repoName, chartName, version)));
    }

    @GetMapping("/{chartName}/values")
    @Operation(summary = "values.yaml")
    public ResponseEntity<ApiSuccessResponse<ChartValuesResponse>> values(
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String repoName,
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String chartName,
            @RequestParam(required = false) @Pattern(regexp = VERSION_PATTERN) String version) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Chart values loaded",
                chartService.getChartValues(repoName, chartName, version)));
    }

    @GetMapping("/{chartName}/readme")
    @Operation(summary = "README.md")
    public ResponseEntity<ApiSuccessResponse<ChartReadmeResponse>> readme(
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String repoName,
            @PathVariable @NotBlank @Pattern(regexp = NAME_PATTERN) @Size(max = 64) String chartName,
            @RequestParam(required = false) @Pattern(regexp = VERSION_PATTERN) String version) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Chart readme loaded",
                chartService.getChartReadme(repoName, chartName, version)));
    }
}
