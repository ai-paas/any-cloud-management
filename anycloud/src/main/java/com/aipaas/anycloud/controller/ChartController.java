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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

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
@RequiredArgsConstructor
@RequestMapping("/charts")
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
            @Parameter(description = "Helm repository 이름", required = true, example = "chart-museum-external")
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
            @Parameter(description = "Helm repository 이름", required = true, example = "chart-museum-external")
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

    @PostMapping(value = "/{repoName}/{chartName}/deploy", consumes = "multipart/form-data")
    @Operation(summary = "차트 배포", description = "ProcessBuilder를 사용하여 Helm CLI(helm install/upgrade)를 호출하여 차트를 배포합니다. values.yaml 파일 업로드가 가능합니다.")
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
            @Parameter(description = "차트 배포 요청 데이터")
            @ModelAttribute ChartDeployDto deployDto) {

        log.info("Deploying chart: {}/{} as release: {} to cluster: {}", 
                repoName, chartName, deployDto.getReleaseName(), deployDto.getClusterId());

        ChartDeployResponseDto deployResponse = chartService.deployChart(
                repoName, chartName, deployDto.getReleaseName(), deployDto.getClusterId(), 
                deployDto.getNamespace(), deployDto.getVersion(), deployDto.getValuesFile());
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, deployResponse));
    }

    @GetMapping("/{repoName}/{chartName}/status")
    @Operation(summary = "차트 배포 상태 조회", description = "Helm CLI를 사용하여 특정 릴리즈의 배포 상태를 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "배포 상태 조회 성공"),
        @ApiResponse(responseCode = "404", description = "릴리즈를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getChartStatus(
            @Parameter(description = "Helm repository 이름", required = true, example = "my-repo")
            @PathVariable String repoName,
            @Parameter(description = "차트 이름", required = true, example = "nginx")
            @PathVariable String chartName, 
            @Parameter(description = "릴리즈 이름", required = true, example = "nginx-test-release")
            @RequestParam String releaseName,
            @Parameter(description = "클러스터 ID", required = true, example = "cluster-001")
            @RequestParam String clusterId,
            @Parameter(description = "네임스페이스", example = "default")
            @RequestParam(required = false) String namespace) {

        log.info("Getting chart status for release: {} in cluster: {}", releaseName, clusterId);

        ChartDeployResponseDto status = chartService.getChartStatus(releaseName, clusterId, namespace);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, status));
    }

    @GetMapping("/releases")
    @Operation(summary = "Helm 릴리즈 목록 조회", description = "Helm CLI를 사용하여 클러스터의 모든 릴리즈 목록을 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "릴리즈 목록 조회 성공"),
        @ApiResponse(responseCode = "404", description = "클러스터를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<ResultResponse> getReleases(
            @Parameter(description = "클러스터 ID", required = true, example = "cluster-001")
            @RequestParam String clusterId,
            @Parameter(description = "네임스페이스 (선택사항)", example = "default")
            @RequestParam(required = false) String namespace) {

        log.info("Getting releases for cluster: {}, namespace: {}", clusterId, namespace);

        ChartReleasesResponseDto releasesResponse = chartService.getReleases(clusterId, namespace);
        return ResponseEntity.ok(ResultResponse.of(SuccessCode.OK, releasesResponse));
    }

    @GetMapping("/releases/{releaseName}/resources")
    @Operation(summary = "차트 리소스 목록 조회", description = "Helm CLI를 사용하여 특정 릴리즈의 리소스 목록을 조회합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "리소스 목록 조회 성공"),
        @ApiResponse(responseCode = "404", description = "릴리즈를 찾을 수 없음"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public ResponseEntity<?> getChartResources(
            @Parameter(description = "클러스터 ID", required = true, example = "cluster-001")
            @RequestParam String clusterId,
            @Parameter(description = "네임스페이스", required = true, example = "default")
            @RequestParam String namespace,
            @Parameter(description = "릴리즈 이름", required = true, example = "nginx-test-release")
            @PathVariable String releaseName) {

        log.info("Getting chart resources for release: {} in cluster: {}", releaseName, clusterId);
    
        return new ResponseEntity<>(chartService.getHelmResources(clusterId, namespace, releaseName),
        new HttpHeaders(), HttpStatus.OK);
    }
}
