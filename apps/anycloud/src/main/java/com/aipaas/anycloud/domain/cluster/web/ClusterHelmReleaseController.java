package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.chart.ChartService;
import com.aipaas.anycloud.domain.chart.api.response.ChartHistoryResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReleasesResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartStatusResponse;
import com.aipaas.anycloud.domain.cluster.api.request.HelmReleaseOperationRequest;
import com.aipaas.anycloud.domain.cluster.api.request.InstallHelmReleaseRequest;
import com.aipaas.anycloud.domain.kube.Namespaces;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Cluster 하위 Helm release 자원.
 * <ul>
 *   <li>GET    /v1/clusters/{c}/helm-releases                       — list (namespace 필터)</li>
 *   <li>POST   /v1/clusters/{c}/helm-releases                       — install (chart + version + valuesYaml)</li>
 *   <li>GET    /v1/clusters/{c}/helm-releases/{r}                   — status</li>
 *   <li>GET    /v1/clusters/{c}/helm-releases/{r}/revisions         — history</li>
 *   <li>POST   /v1/clusters/{c}/helm-releases/{r}/operations        — rollback</li>
 *   <li>GET    /v1/clusters/{c}/helm-releases/{r}/resources         — release 의 K8s 리소스</li>
 *   <li>DELETE /v1/clusters/{c}/helm-releases/{r}?keepHistory=&wait= — uninstall (LRO)</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/clusters/{clusterName}/helm-releases")
@Tag(name = "Cluster Helm Releases (v1)", description = "Cluster 의 Helm release lifecycle")
@Validated
public class ClusterHelmReleaseController {

    private final ChartService chartService;
    private final OperationService operationService;

    @GetMapping
    @Operation(summary = "release list")
    public ResponseEntity<ApiSuccessResponse<ChartReleasesResponse>> list(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(), "Helm releases loaded", chartService.getReleases(clusterName, namespace)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "release 설치 (JSON — values 객체 또는 valuesYaml 문자열)",
            description = "values 입력은 최대 하나만 제공. values (JSON object, 권장) 또는 valuesYaml (raw string). "
                    + "큰 values 파일은 multipart variant 사용 권장.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "install accepted — Location: /v1/operations/{id}"),
        @ApiResponse(responseCode = "400", description = "chart 형식 위반 / values+valuesYaml 동시 지정 / YAML 직렬화 실패")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> installJson(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = InstallHelmReleaseRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "values 객체 (권장)",
                                                        value =
                                                                """
											{
											  "releaseName": "ingress",
											  "chart": "bitnami/nginx",
											  "version": "15.3.0",
											  "namespace": "web",
											  "values": {
											    "replicaCount": 3,
											    "image": {"repository": "nginx", "tag": "1.25"},
											    "service": {"type": "LoadBalancer", "port": 80}
											  }
											}"""),
                                                @ExampleObject(
                                                        name = "valuesYaml 문자열",
                                                        value =
                                                                """
											{
											  "releaseName": "ingress",
											  "chart": "bitnami/nginx",
											  "namespace": "web",
											  "valuesYaml": "replicaCount: 3\\nimage:\\n  repository: nginx\\n  tag: \\"1.25\\"\\nservice:\\n  type: LoadBalancer\\n"
											}"""),
                                                @ExampleObject(
                                                        name = "default values (values 미지정)",
                                                        value =
                                                                """
											{
											  "releaseName": "ingress",
											  "chart": "bitnami/nginx",
											  "namespace": "web"
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    InstallHelmReleaseRequest body) {
        String yaml = resolveValuesYaml(body.getValues(), body.getValuesYaml());
        return doInstall(
                clusterName, body.getReleaseName(), body.getChart(), body.getVersion(), body.getNamespace(), yaml);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "release 설치 (multipart — values.yaml 파일 업로드)",
            description = "UI 의 파일 업로드 / 큰 values 파일에 적합. valuesFile 미제공 시 default values 로 설치.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "install accepted"),
        @ApiResponse(responseCode = "400", description = "chart 형식 위반 / 파일 읽기 실패")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> installMultipart(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @Parameter(description = "릴리즈 이름")
                    @org.springframework.web.bind.annotation.RequestParam
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    String releaseName,
            @Parameter(description = "chart 참조 \"<repo>/<chart>\"")
                    @org.springframework.web.bind.annotation.RequestParam
                    @NotBlank
                    @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
                    String chart,
            @Parameter(description = "chart version (선택)")
                    @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String version,
            @Parameter(description = "namespace (선택, default)")
                    @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String namespace,
            @Parameter(description = "values.yaml 파일 (선택, multipart file)")
                    @org.springframework.web.bind.annotation.RequestParam(required = false)
                    MultipartFile valuesFile) {
        String yaml = null;
        if (valuesFile != null && !valuesFile.isEmpty()) {
            try {
                yaml = new String(valuesFile.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("valuesFile read failed: " + e.getMessage());
            }
        }
        return doInstall(clusterName, releaseName, chart, version, namespace, yaml);
    }

    private ResponseEntity<ApiSuccessResponse<OperationResponse>> doInstall(
            String clusterName,
            String releaseName,
            String chartRef,
            String version,
            String namespace,
            String valuesYaml) {
        String[] parts = chartRef.split("/", 2);
        String repo = parts[0];
        String chart = parts[1];
        String ns = Namespaces.defaultIfBlank(namespace);

        var op = operationService.start(OperationType.INSTALL_HELM_RELEASE, "helmRelease", releaseName, null, 1);
        try {
            chartService.deployChartFromYaml(repo, chart, releaseName, clusterName, ns, version, valuesYaml);
            operationService.markRunning(op.getId());
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.getId()))
                .body(ApiSuccessResponse.of(
                                HttpStatus.ACCEPTED.value(), "Helm install accepted", OperationResponse.from(op))
                        .withLinks(Map.of(
                                "self", "/v1/operations/" + op.getId(),
                                "resource", "/v1/clusters/" + clusterName + "/helm-releases/" + releaseName)));
    }

    /** values 객체 또는 valuesYaml 문자열 중 하나를 받아 YAML 문자열로 정규화. */
    private static String resolveValuesYaml(Map<String, Object> values, String rawYaml) {
        boolean hasObject = values != null && !values.isEmpty();
        boolean hasYaml = rawYaml != null && !rawYaml.isBlank();
        if (hasObject && hasYaml) {
            throw new IllegalArgumentException("values 와 valuesYaml 을 동시에 지정할 수 없습니다. 하나만 보내세요.");
        }
        if (hasObject) {
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); // 보기 좋은 들여쓰기
            opts.setPrettyFlow(true);
            opts.setIndent(2);
            return new Yaml(opts).dump(values);
        }
        return rawYaml; // null/empty 면 helm 이 default values 사용
    }

    @GetMapping("/{releaseName}")
    @Operation(summary = "release status")
    public ResponseEntity<ApiSuccessResponse<ChartStatusResponse>> getOne(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Helm release status loaded",
                chartService.getChartStatus(releaseName, clusterName, namespace)));
    }

    @GetMapping("/{releaseName}/revisions")
    @Operation(summary = "release history (revision 이력)")
    public ResponseEntity<ApiSuccessResponse<ChartHistoryResponse>> history(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @Parameter(description = "최근 N revision (default 10)")
                    @Min(1)
                    @RequestParam(required = false, defaultValue = "10")
                    int max) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "Helm release history loaded",
                chartService.getReleaseHistory(clusterName, releaseName, namespace, max)));
    }

    @PostMapping("/{releaseName}/operations")
    @Operation(summary = "release 액션 operation (rollback)")
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> createOperation(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = HelmReleaseOperationRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "직전 성공 revision 으로 rollback",
                                                        value =
                                                                """
											{"type": "rollback", "revision": 0, "wait": true}"""),
                                                @ExampleObject(
                                                        name = "특정 revision 으로 rollback",
                                                        value =
                                                                """
											{"type": "rollback", "revision": 3, "wait": false}""")
                                            }))
                    @Valid
                    @RequestBody
                    HelmReleaseOperationRequest body) {
        int revision = body.getRevision() == null ? 0 : body.getRevision();
        boolean waitForReady = Boolean.TRUE.equals(body.getWait());
        var op = operationService.start(
                OperationType.ROLLBACK_HELM_RELEASE, "helmRelease", releaseName, "{\"revision\":" + revision + "}", 1);
        try {
            operationService.markRunning(op.getId());
            var status = chartService.rollbackRelease(clusterName, releaseName, revision, namespace, waitForReady);
            operationService.complete(
                    op.getId(), "{\"status\":\"" + (status.getStatus() == null ? "" : status.getStatus()) + "\"}");
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Rollback completed", OperationResponse.from(op)));
    }

    /**
     * 3 — Helm release UPGRADE. 기존 release 를 새 chart version / values 로 갱신.
     *
     * <p>install 와 동일한 chart resolution + agent release lock 공유. release 미존재 시 400 +
     * HELM_NOT_FOUND.
     *
     * <p>옵션:
     * <ul>
     *   <li>{@code atomic} (default true) — 실패 시 자동 rollback. production 권장.</li>
     *   <li>{@code reuseValues} (default false) — 기존 values 보존 + 새 values merge.</li>
     *   <li>{@code resetValues} (default false) — 기존 values 모두 reset (chart default + 새 values).
     *       reuseValues 우선. 둘 다 true 면 resetValues 적용.</li>
     * </ul>
     */
    @org.springframework.web.bind.annotation.PutMapping(
            value = "/{releaseName}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "release 업그레이드 — 새 chart version / values 로 갱신")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "upgrade OK — revision 증가, status=deployed"),
        @ApiResponse(responseCode = "400", description = "release 미존재 (HELM_NOT_FOUND), 또는 chart resolution 실패"),
        @ApiResponse(responseCode = "503", description = "Agent unreachable")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> upgradeRelease(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    com.aipaas.anycloud.domain.cluster.api.request
                                                                            .UpgradeHelmReleaseRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "버전만 업그레이드 (values 보존)",
                                                        value =
                                                                """
											{
											  "chart": "bitnami/nginx",
											  "version": "15.4.0",
											  "namespace": "web",
											  "reuseValues": true,
											  "atomic": true
											}"""),
                                                @ExampleObject(
                                                        name = "values 전체 교체",
                                                        value =
                                                                """
											{
											  "chart": "bitnami/nginx",
											  "version": "15.4.0",
											  "namespace": "web",
											  "values": {"replicaCount": 5, "service": {"type": "ClusterIP"}},
											  "atomic": true
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    com.aipaas.anycloud.domain.cluster.api.request.UpgradeHelmReleaseRequest body) {
        String yaml = resolveValuesYaml(body.getValues(), body.getValuesYaml());
        String repo = "";
        String chart = body.getChart();
        int slash = chart == null ? -1 : chart.indexOf('/');
        if (slash > 0) {
            repo = chart.substring(0, slash);
            chart = chart.substring(slash + 1);
        }
        boolean atomic = body.getAtomic() == null ? true : body.getAtomic();
        boolean reuseValues = Boolean.TRUE.equals(body.getReuseValues());
        boolean resetValues = Boolean.TRUE.equals(body.getResetValues());

        var op = operationService.start(
                OperationType.UPGRADE_HELM_RELEASE,
                "helmRelease",
                releaseName,
                "{\"chart\":\"" + repo + "/" + chart + "\",\"version\":\"" + body.getVersion() + "\"}",
                1);
        try {
            operationService.markRunning(op.getId());
            var status = chartService.upgradeRelease(
                    clusterName,
                    releaseName,
                    repo,
                    chart,
                    body.getVersion(),
                    body.getNamespace(),
                    yaml,
                    atomic,
                    reuseValues,
                    resetValues);
            operationService.complete(
                    op.getId(), "{\"status\":\"" + (status.getStatus() == null ? "" : status.getStatus()) + "\"}");
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Upgrade completed", OperationResponse.from(op)));
    }

    @GetMapping("/{releaseName}/resources")
    @Operation(summary = "release 가 만든 K8s 리소스 목록")
    public ResponseEntity<ApiSuccessResponse<Object>> resources(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace) {
        var list = chartService.getHelmResources(clusterName, namespace, releaseName);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Helm release resources loaded", list));
    }

    @DeleteMapping("/{releaseName}")
    @Operation(
            summary = "release uninstall (LRO — helm uninstall)",
            description = "지정 release 를 cluster 에서 제거. 기본은 history 도 같이 삭제 (idempotent 재설치 가능). "
                    + "`keepHistory=true` 면 helm 의 revision 이력을 보존 (롤백 추적용). "
                    + "`wait=true` 면 모든 K8s 자원이 실제로 사라질 때까지 대기.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "uninstall accepted — Location: /v1/operations/{id}"),
        @ApiResponse(responseCode = "404", description = "release 또는 cluster 미존재"),
        @ApiResponse(responseCode = "500", description = "helm CLI 실행 실패")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> uninstall(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String releaseName,
            @Parameter(description = "namespace (선택, default)")
                    @RequestParam(required = false)
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @Parameter(description = "true 면 revision 이력 보존 (--keep-history)")
                    @RequestParam(name = "keepHistory", required = false, defaultValue = "false")
                    boolean keepHistory,
            @Parameter(description = "true 면 자원 삭제 완료까지 대기 (--wait)")
                    @RequestParam(name = "wait", required = false, defaultValue = "false")
                    boolean waitForReady) {
        var op = operationService.start(
                OperationType.UNINSTALL_HELM_RELEASE,
                "helmRelease",
                releaseName,
                "{\"keepHistory\":" + keepHistory + ",\"wait\":" + waitForReady + "}",
                1);
        try {
            operationService.markRunning(op.getId());
            var status = chartService.uninstallRelease(clusterName, releaseName, namespace, keepHistory, waitForReady);
            operationService.complete(
                    op.getId(),
                    "{\"status\":\"" + (status.getStatus() == null ? "" : status.getStatus()) + "\",\"keepHistory\":"
                            + keepHistory + "}");
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.getId()))
                .body(ApiSuccessResponse.of(
                                HttpStatus.ACCEPTED.value(), "Helm uninstall accepted", OperationResponse.from(op))
                        .withLinks(Map.of(
                                "self", "/v1/operations/" + op.getId(),
                                "resource", "/v1/clusters/" + clusterName + "/helm-releases/" + releaseName)));
    }
}
