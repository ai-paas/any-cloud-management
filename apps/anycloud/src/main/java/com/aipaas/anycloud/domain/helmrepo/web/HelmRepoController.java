package com.aipaas.anycloud.domain.helmrepo.web;

import com.aipaas.anycloud.common.web.ActionResponse;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoService;
import com.aipaas.anycloud.domain.helmrepo.api.request.CreateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.request.UpdateHelmRepoRequest;
import com.aipaas.anycloud.domain.helmrepo.api.response.HelmRepoDetailResponse;
import com.aipaas.anycloud.domain.helmrepo.api.response.HelmRepoListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/helm-repos")
@Tag(name = "Helm Repos (v1)", description = "Helm chart 저장소 CRUD")
public class HelmRepoController {

    private final HelmRepoService helmRepoService;

    /**
     * [HelmRepoController] 헬름 저장소 목록 조회 함수
     *
     * @return 헬름 저장소 전체 목록을 반환합니다.
     *         <p>
     */
    @GetMapping("")
    @Operation(summary = "헬름 저장소 목록 조회", description = "헬름 저장소 전체 목록을 조회합니다.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "헬름 저장소 목록 조회 성공"),
                @ApiResponse(responseCode = "400", description = "Repository 정보를 찾을 수 없음"),
                @ApiResponse(responseCode = "500", description = "서버 오류")
            })
    public ResponseEntity<ApiSuccessResponse<PagedData<HelmRepoListResponse>>> getHelmRepos() {
        // Domain method 사용 — JPA proxy / lazy loading 노출 제거.
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "Helm repositories loaded",
                        PagedData.of(helmRepoService.findAllDomain().stream()
                                .map(this::toListDto)
                                .toList())),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    /**
     * [HelmRepoController] 헬름 저장소 단일 조회 함수
     *
     * @param helmRepoName 헬름 저장소 이름
     * @return 헬름 저장소 정보를 반환합니다.
     *         <p>
     */
    @GetMapping("/{repoName}")
    @Operation(summary = "헬름 저장소 조회", description = "헬름 저장소를 조회합니다.")
    public ResponseEntity<ApiSuccessResponse<HelmRepoDetailResponse>> getHelmRepo(
            @Parameter(description = "Helm repository 이름", required = true, example = "chart-museum-external")
                    @PathVariable
                    String repoName) {
        // Domain method 사용 — 미존재 시 404.
        var domain = helmRepoService
                .findDomainByName(repoName)
                .orElseThrow(() -> new com.aipaas.anycloud.common.error.exception.EntityNotFoundException(
                        "HelmRepo with Name " + repoName + " Not Found."));
        return new ResponseEntity<>(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Helm repository loaded", toDetailDto(domain)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    /**
     * [HelmRepoController] 헬름 저장소 생성 함수
     *
     * @param cluster 헬름 저장소 생성 정보
     * @return 헬름 저장소를 생성합니다.
     *         <p>
     */
    @PostMapping("")
    @Operation(summary = "헬름 저장소 생성", description = "헬름 저장소를 생성합니다.")
    public ResponseEntity<ApiSuccessResponse<ActionResponse>> createHelmRepo(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateHelmRepoRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "Bitnami (public)",
                                                        value =
                                                                """
											{
											  "name": "bitnami",
											  "url": "https://charts.bitnami.com/bitnami"
											}"""),
                                                @ExampleObject(
                                                        name = "사설 (basic auth)",
                                                        value =
                                                                """
											{
											  "name": "internal",
											  "url": "https://charts.example.internal",
											  "username": "ci-bot",
											  "password": "***"
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    CreateHelmRepoRequest cluster) {
        HttpStatus status = helmRepoService.createHelmRepo(cluster);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        status.value(),
                        "Helm repository created",
                        ActionResponse.builder()
                                .resourceType("helmRepository")
                                .resourceId(cluster.getName())
                                .operation("create")
                                .state(status.name())
                                .build()),
                new HttpHeaders(),
                status);
    }

    /**
     * [HelmRepoController] 헬름 저장소 삭제 함수
     *
     * @param clusterName 헬름 저장소 이름
     * @return 헬름 저장소 삭제합니다.
     *         <p>
     */
    @DeleteMapping("/{repoName}")
    @Operation(summary = "헬름 저장소 정보 삭제", description = "헬름 저장소 정보를 삭제합니다.")
    public ResponseEntity<ApiSuccessResponse<ActionResponse>> deletePackage(@PathVariable String repoName) {
        String clusterName = repoName;
        HttpStatus status = helmRepoService.deleteHelmRepo(clusterName);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        status.value(),
                        "Helm repository deleted",
                        ActionResponse.builder()
                                .resourceType("helmRepository")
                                .resourceId(clusterName)
                                .operation("delete")
                                .state(status.name())
                                .build()),
                new HttpHeaders(),
                status);
    }

    /**
     * Helm repo 부분 갱신. null 필드는 현재 값 유지.
     *
     * <p>name 변경 미지원 — URL identity 보존을 위해 delete + create 패턴 사용.
     *
     * <p>autoAllowlist 자동 sync 제거. chart 제한 원하면 별도로
     * PUT/PATCH /v1/admin/clusters/{c}/agent-policy 호출.
     */
    @PatchMapping("/{repoName}")
    @Operation(
            summary = "헬름 저장소 정보 부분 갱신",
            description = "변경 가능 필드: url / username / password / caFile / insecureSkipTlsVerify. "
                    + "null 필드는 현재 값 유지 (partial update). "
                    + "<br><br>"
                    + "<b>name 변경 미지원</b> — repository 이름은 URL identity (path variable). "
                    + "이름 변경이 필요하면 DELETE + POST 패턴 사용.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "부분 갱신 성공"),
        @ApiResponse(responseCode = "404", description = "Helm repo 미존재")
    })
    public ResponseEntity<ApiSuccessResponse<ActionResponse>> updateHelmRepo(
            @PathVariable String repoName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = UpdateHelmRepoRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "URL + auth 갱신",
                                                        value =
                                                                """
											{
											  "url": "https://charts.new.example.com",
											  "username": "ci-bot",
											  "password": "new-secret"
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    UpdateHelmRepoRequest update) {
        HttpStatus status = helmRepoService.updateHelmRepo(repoName, update);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        status.value(),
                        "Helm repository updated",
                        ActionResponse.builder()
                                .resourceType("helmRepository")
                                .resourceId(repoName)
                                .operation("update")
                                .state(status.name())
                                .build()),
                new HttpHeaders(),
                status);
    }

    private HelmRepoListResponse toListDto(com.aipaas.anycloud.domain.helmrepo.model.HelmRepo d) {
        return new HelmRepoListResponse(
                d.name(), d.url(), d.isTlsVerificationDisabled(), d.createdAt(), d.source(), d.tags());
    }

    private HelmRepoDetailResponse toDetailDto(com.aipaas.anycloud.domain.helmrepo.model.HelmRepo d) {
        return new HelmRepoDetailResponse(
                d.name(),
                d.url(),
                d.isTlsVerificationDisabled(),
                d.username(),
                d.password() == null || d.password().isBlank() ? null : "****",
                null,
                null,
                d.caFile(),
                d.createdAt(),
                d.updatedAt(),
                d.source(),
                d.tags());
    }
}
