package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.error.ResultResponse;
import com.aipaas.anycloud.error.enums.SuccessCode;
import com.aipaas.anycloud.model.dto.request.ChartDeployDto;
import com.aipaas.anycloud.model.dto.response.*;
import com.aipaas.anycloud.service.ChartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * <pre>
 * ClassName : ChartController
 * Type : class
 * Description : Helm 차트 관련 API를 제공하는 컨트롤러입니다.
 * Related : ChartService
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/charts")
@RequiredArgsConstructor
@Tag(name = "Chart", description = "Helm 차트 관련 API")
public class ChartController {

    private final ChartService chartService;

    @GetMapping("/{repoName}")
    @Operation(summary = "차트 목록 조회", description = "DB에서 repoName로 RepositoryEntity 조회 후 해당 url에서 index.yaml을 다운로드하여 차트 목록을 반환합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "차트 목록 조회 성공"),
        @ApiResponse(responseCode = "404", description = "Repository를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getChartList(
            @Parameter(description = "Helm repository 이름", required = true, example = "chart-museum")
			@PathVariable String repoName) {

        log.info("Getting chart list for repository: {}", repoName);

        ChartListDto chartList = chartService.getChartList(repoName);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, chartList));
    }

    @GetMapping("/{repoName}/{chartName}/detail")
    @Operation(
        summary = "차트 상세 조회",
        description = "DB에서 repoName 또는 이름으로 RepositoryEntity 조회 후 해당 url에서 index.yaml을 다운로드하여 특정 차트 상세 정보를 반환합니다."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "차트 상세 조회 성공"),
        @ApiResponse(responseCode = "404", description = "Repository 또는 차트를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getChartDetail(
            @Parameter(description = "Helm repository 이름", required = true, example = "chart-museum")
            @PathVariable String repoName,
            @Parameter(description = "조회할 차트 이름", required = true, example = "nginx")
            @PathVariable String chartName) {

    log.info("Getting chart detail for repository: {}, chart: {}", repoName, chartName);

    ChartDetailDto chartDetail = chartService.getChartDetail(repoName, chartName);
    return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, chartDetail));
}

    @GetMapping("/{repoName}/{chartName}/values")
    @Operation(summary = "차트 values.yaml 조회", description = "Helm CLI를 사용하여 지정된 차트의 values.yaml 내용을 실시간으로 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "values.yaml 조회 성공"),
        @ApiResponse(responseCode = "404", description = "Repository 또는 Chart를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getChartValues(
            @Parameter(description = "Helm repository 이름", required = true, example = "my-repo")
            @PathVariable String repoName,
            @Parameter(description = "차트 이름", required = true, example = "nginx")
            @PathVariable String chartName,
            @Parameter(description = "차트 버전 (선택사항)", example = "15.4.4")
            @RequestParam(required = false) String version) {

        log.info("Getting values for chart: {}/{}, version: {}", repoName, chartName, version);

        ChartValuesDto chartValues = chartService.getChartValues(repoName, chartName, version);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, chartValues));
    }

    @GetMapping("/{repoName}/{chartName}/readme")
    @Operation(summary = "차트 README.md 조회", description = "Helm CLI를 사용하여 지정된 차트의 README.md 내용을 실시간으로 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "README.md 조회 성공"),
        @ApiResponse(responseCode = "404", description = "Repository 또는 Chart를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getChartReadme(
            @Parameter(description = "Helm repository 이름", required = true, example = "my-repo")
            @PathVariable String repoName,
            @Parameter(description = "차트 이름", required = true, example = "nginx")
            @PathVariable String chartName,
            @Parameter(description = "차트 버전 (선택사항)", example = "15.4.4")
            @RequestParam(required = false) String version) {

        log.info("Getting README for chart: {}/{}, version: {}", repoName, chartName, version);

        ChartReadmeDto chartReadme = chartService.getChartReadme(repoName, chartName, version);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, chartReadme));
    }

    @PostMapping("/{repoName}/{chartName}/deploy")
    @Operation(summary = "차트 배포", description = "ProcessBuilder를 사용하여 Helm CLI(helm install/upgrade)를 호출하여 차트를 배포합니다. namespace 지정 및 values override가 가능합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "차트 배포 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청"),
        @ApiResponse(responseCode = "404", description = "Repository 또는 Chart를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "배포 실패")
    })
    public ResponseEntity<ResultResponse> deployChart(
            @Parameter(description = "Helm repository 이름", required = true, example = "my-repo")
            @PathVariable String repoName,
            @Parameter(description = "차트 이름", required = true, example = "nginx")
            @PathVariable String chartName,
            @Valid @RequestBody ChartDeployDto deployDto) {

        log.info("Deploying chart: {}/{} as release: {}", repoName, chartName, deployDto.getReleaseName());

        ChartDeployResponseDto deployResponse = chartService.deployChart(repoName, chartName, deployDto);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, deployResponse));
    }
}
