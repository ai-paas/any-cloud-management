package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterOperationRequest;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterCapabilitiesRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.response.ClusterRegistrationResponse;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.cluster.model.BootstrapInfo;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryQueryService;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합 cluster 자원 API (RESTful 재설계).
 * <ul>
 *   <li>GET    /v1/clusters                                — 통합 list</li>
 *   <li>GET    /v1/clusters/{name}                         — 단일 조회</li>
 *   <li>POST   /v1/clusters                                — VM 생성 또는 외부 등록 (body source)</li>
 *   <li>PATCH  /v1/clusters/{name}                         — state 변경 (scale / upgrade)</li>
 *   <li>DELETE /v1/clusters/{name}                         — destroy</li>
 *   <li>POST   /v1/clusters/{name}/operations              — 액션 operation (retry/refresh)</li>
 *   <li>GET    /v1/clusters/{name}/operations              — 이 cluster 의 operation 이력</li>
 *   <li>POST   /v1/clusters/{name}/connectivity-checks     — K8s API 연결 검사 (결과 자원)</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/clusters")
@Tag(name = "Clusters (v1)", description = "통합 cluster 자원 — VM provisioned + 외부 registered")
@Validated
public class ClusterController {

    private final ClusterFacade clusterFacade;
    private final OperationService operationService;
    /** capability sync 변경 (PATCH /capabilities) 에 사용. observability starter SPI. */
    private final ClusterCapabilitiesSink clusterCapabilitiesSink;

    private final VmClusterStateHistoryQueryService stateHistoryQueryService;
    // agent-led registration — token 발급 + helm install 명령 노출.
    private final AgentApiManagedInstaller agentApiManagedInstaller;

    // =============== Collection ===============

    @GetMapping
    @Operation(
            summary = "cluster list",
            description = "source 미지정 시 vm + registered 통합 반환. "
                    + "page meta (pageSize / nextPageToken / totalEstimate) 가 응답 envelope 에 포함됨.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공 — PagedData<UnifiedClusterResponse> + page meta"),
        @ApiResponse(responseCode = "400", description = "filter 정규식 위반 (provider/environment/status/source)")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<UnifiedClusterResponse>>> list(
            @Parameter(description = "필터: vm | registered (미지정 시 전체)")
                    @RequestParam(required = false)
                    @Pattern(regexp = "^(vm|registered)$")
                    String source,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.PROVIDER_PATTERN) String provider,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.ENVIRONMENT_PATTERN)
                    String environment,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.STATUS_PATTERN) String status,
            @Parameter(description = "페이지 크기 (1-500, default 100)")
                    @RequestParam(name = "pageSize", required = false, defaultValue = "100")
                    @jakarta.validation.constraints.Min(1)
                    @jakarta.validation.constraints.Max(500)
                    int pageSize,
            @Parameter(
                            description =
                                    "다음 페이지 token (opaque — 첫 호출 시 omit, 응답 meta.pagination.nextPageToken 그대로 round-trip). 형식은 service 내부 구현이라 client 가 파싱 금지.")
                    @RequestParam(name = "pageToken", required = false)
                    String pageToken) {
        var paged = clusterFacade.listPaged(source, provider, environment, status, pageSize, pageToken);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Clusters loaded", PagedData.of(paged.items()))
                        .withPagedMeta(pageSize, paged.nextPageToken(), paged.totalEstimate()));
    }

    // =============== Item ===============

    @GetMapping("/{clusterName}")
    @Operation(summary = "단일 cluster 조회 (source 자동 감지)")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "성공 — links{self,operations,helmReleases,namespaces,events} 포함"),
        @ApiResponse(responseCode = "400", description = "clusterName 형식 위반 (RFC 1123 label)"),
        @ApiResponse(responseCode = "404", description = "cluster not found (vm + registered 둘 다 없음)")
    })
    public ResponseEntity<ApiSuccessResponse<UnifiedClusterResponse>> getOne(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        var dto = clusterFacade.getOne(clusterName);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Cluster loaded", dto)
                .withLinks(clusterLinks(clusterName)));
    }

    @PostMapping
    @Operation(
            summary = "cluster 생성 (VM provision 또는 외부 등록)",
            description = "body 의 source=\"vm\"|\"registered\" 로 분기. VM 은 비동기 → 202 + Operation, "
                    + "외부 등록은 즉시 → 201 + Operation(SUCCEEDED). Idempotency-Key 헤더 지원 (24h).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "registered 등록 즉시 완료"),
        @ApiResponse(responseCode = "202", description = "vm provision 비동기 수락 — Location: /v1/operations/{id}"),
        @ApiResponse(responseCode = "400", description = "spec 필드 누락 / clusterName 형식 위반"),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key 충돌 (같은 key + 다른 body)")
    })
    public ResponseEntity<ApiSuccessResponse<ClusterRegistrationResponse>> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "source=vm 이면 Pulumi VM provision (async 202), "
                                    + "source=registered 이면 외부 K8s cluster 등록 (sync 201).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateClusterRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "VM provision (AWS)",
                                                        value =
                                                                """
											{
											  "source": "vm",
											  "clusterName": "demo-aws-01",
											  "spec": {
											    "provider": "aws",
											    "region": "ap-northeast-2",
											    "environment": "dev",
											    "credentialId": "cred-aws-001",
											    "config": {
											      "workerCount": "3",
											      "instanceType": "t3.medium"
											    }
											  }
											}"""),
                                                @ExampleObject(
                                                        name = "Registered (kubeconfig)",
                                                        value =
                                                                """
											{
											  "source": "registered",
											  "clusterName": "imported-aws-01",
											  "spec": {
											    "provider": "AWS",
											    "clusterType": "EKS",
											    "apiServerUrl": "https://kube.example.com:6443",
											    "serverCA": "<base64-CA>",
											    "clientToken": "<bearer-token>",
											    "description": "Imported via raw fields"
											  }
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    CreateClusterRequest request) {
        var op = clusterFacade.createDomain(request);
        boolean async = request.getSource() == CreateClusterRequest.Source.vm;
        HttpStatus code = async ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        String location = "/v1/operations/" + op.id();
        // registered source 면 bootstrap token + install 명령 즉시 발급.
        // vm source 는 비동기 — provisioning 완료 후 별도 endpoint (POST /agent/reinstall) 로 발급.
        BootstrapInfo bootstrap = null;
        if (!async) {
            bootstrap = agentApiManagedInstaller.prepareBootstrap(request.getClusterName());
        }
        Map<String, String> links = async
                ? Map.of(
                        "self", "/v1/operations/" + op.id(),
                        "resource", "/v1/clusters/" + request.getClusterName(),
                        "events", "/v1/operations/" + op.id() + "/events")
                : Map.of(
                        "self", "/v1/operations/" + op.id(),
                        "resource", "/v1/clusters/" + request.getClusterName(),
                        "events", "/v1/operations/" + op.id() + "/events",
                        "manifest", "/v1/clusters/" + request.getClusterName() + "/agent-manifest.yaml");
        return ResponseEntity.status(code)
                .location(URI.create(location))
                .body(ApiSuccessResponse.of(
                                code.value(),
                                async
                                        ? "Cluster creation accepted"
                                        : "Cluster registered — run helmInstallCommand from your kubectl context",
                                new ClusterRegistrationResponse(OperationResponse.from(op), bootstrap))
                        .withLinks(links));
    }

    @PatchMapping("/{clusterName}")
    @Operation(summary = "cluster state 변경 (scale)", description = "spec.workerCount(1..50) 변경.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "변경 수락 — Location 헤더 + Operation"),
        @ApiResponse(responseCode = "400", description = "workerCount 범위 외 / spec 비어 있음"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> patch(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "spec 의 변경 항목만 포함.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PatchClusterRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "Scale (worker 수 변경)",
                                                        value = """
											{"spec": {"workerCount": 5}}""")
                                            }))
                    @Valid
                    @RequestBody
                    PatchClusterRequest request) {
        var op = clusterFacade.patchDomain(clusterName, request);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                                HttpStatus.ACCEPTED.value(), "Cluster update accepted", OperationResponse.from(op))
                        .withLinks(Map.of(
                                "self", "/v1/operations/" + op.id(),
                                "resource", "/v1/clusters/" + clusterName)));
    }

    // Capability sync 변경 endpoint. PATCH /clusters/{c} 의 비동기 의미와 분리.
    @PatchMapping("/{clusterName}/capabilities")
    @Operation(
            summary = "cluster capability flag 수동 설정 (sync)",
            description = "현재는 hasGpuNodes 만 지원. agent 의 자동 backfill (heartbeat 기반) 과 별개로 "
                    + "운영자가 즉시 GPU 노드 보유 여부를 명시. 다음 cluster ACTIVE 시 dcgm-exporter 자동 설치 여부 결정.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Capability 적용됨"),
        @ApiResponse(responseCode = "400", description = "변경할 필드 없음"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> patchCapabilities(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "변경할 capability flag 들 — null 필드는 변경 안 함.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PatchClusterCapabilitiesRequest.class),
                                            examples = {
                                                @ExampleObject(name = "GPU 활성", value = "{\"hasGpuNodes\": true}"),
                                                @ExampleObject(name = "GPU 해제", value = "{\"hasGpuNodes\": false}")
                                            }))
                    @Valid
                    @RequestBody
                    PatchClusterCapabilitiesRequest request) {
        if (request == null || request.hasGpuNodes() == null) {
            throw new IllegalArgumentException("at least one capability field required");
        }
        clusterCapabilitiesSink.setHasGpuNodes(clusterName, request.hasGpuNodes());
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "capability updated",
                Map.of("clusterName", clusterName, "hasGpuNodes", request.hasGpuNodes())));
    }

    @DeleteMapping("/{clusterName}")
    @Operation(summary = "cluster 삭제 (비동기)")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "삭제 수락 — Location 헤더 + Operation"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> delete(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        var op = clusterFacade.deleteDomain(clusterName);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "Cluster delete accepted", OperationResponse.from(op)));
    }

    // =============== Sub-resources ===============

    @PostMapping("/{clusterName}/operations")
    @Operation(
            summary = "액션 operation 생성 (retry/refresh)",
            description = "type=retryWorkflow|retryRegistration|refreshStatus. **항상 202 + Operation resource** "
                    + "반환 (Google AIP LRO 패턴). sync 한 op (refreshStatus) 는 응답 body 의 state=SUCCEEDED "
                    + "필드로 즉시 식별 가능 — 클라이언트는 state 만 보면 polling 여부 판단.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description =
                        "Operation 수락. body 의 state 가 SUCCEEDED 면 즉시 완료, " + "PENDING/RUNNING 이면 SSE 또는 polling 으로 추적"),
        @ApiResponse(responseCode = "400", description = "지원하지 않는 type"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> createOperation(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateClusterOperationRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "Retry workflow",
                                                        value = """
											{"type": "retryWorkflow"}"""),
                                                @ExampleObject(
                                                        name = "Retry registration",
                                                        value = """
											{"type": "retryRegistration"}"""),
                                                @ExampleObject(
                                                        name = "Refresh status (sync — 202 + state=SUCCEEDED)",
                                                        value = """
											{"type": "refreshStatus"}""")
                                            }))
                    @Valid
                    @RequestBody
                    CreateClusterOperationRequest request) {
        // UX #8: 항상 202 + Operation — sync/async 양 케이스를 동일 패턴으로 처리. 클라이언트는
        // 응답 body 의 state (PENDING/RUNNING/SUCCEEDED/FAILED) 로 polling 필요 여부 판단.
        var op = clusterFacade.createOperationDomain(
                clusterName, request.getType().name());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "Operation accepted", OperationResponse.from(op)));
    }

    @GetMapping("/{clusterName}/operations")
    @Operation(summary = "이 cluster 의 operation 이력 (최신 순)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PagedData<OperationResponse>"),
        @ApiResponse(responseCode = "400", description = "pageSize 범위 외")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<OperationResponse>>> listOperations(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @RequestParam(name = "pageSize", required = false, defaultValue = "50") @Min(1) @Max(500) int pageSize) {
        var items = operationService.listByResource("cluster", clusterName, pageSize).stream()
                .map(OperationResponse::from)
                .toList();
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Operations loaded", PagedData.of(items)));
    }

    @GetMapping("/{clusterName}/state-history")
    @Operation(
            summary = "이 cluster 의 state transition 이력 (최신 순)",
            description = "VmCluster workflow 의 status 변화 audit. 각 row 는 from/to/reason/principal 포함. "
                    + "valid=false 면 state machine graph 가 invalid 라고 판정한 transition (observation mode).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "state transition rows (최신 → 과거)"),
        @ApiResponse(responseCode = "400", description = "pageSize 범위 외")
    })
    public ResponseEntity<
                    ApiSuccessResponse<PagedData<com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory>>>
            listStateHistory(
                    @PathVariable
                            @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                            @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                            String clusterName,
                    @RequestParam(name = "pageSize", required = false, defaultValue = "50") @Min(1) @Max(500)
                            int pageSize) {
        // Domain record 반환 — JPA proxy 직렬화 회피.
        var items = stateHistoryQueryService.listRecentDomain(clusterName, pageSize);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "State history loaded", PagedData.of(items)));
    }

    @PostMapping("/{clusterName}/connectivity-checks")
    @Operation(
            summary = "K8s API 연결 검사 (검사 결과 자체가 자원)",
            description = "검사 즉시 수행, 결과 record 반환 (connected/checkedAt). 향후 비동기 전환 시 LRO 패턴 적용 가능.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "검사 완료 — connected=true|false 포함"),
        @ApiResponse(responseCode = "404", description = "cluster not found")
    })
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> checkConnectivity(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        boolean connected = clusterFacade.checkConnectivity(clusterName);
        Map<String, Object> result = Map.of(
                "clusterName", clusterName,
                "connected", connected,
                "checkedAt", java.time.Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.of(HttpStatus.CREATED.value(), "Connectivity check completed", result));
    }

    @GetMapping(value = "/{clusterName}/agent-manifest.yaml", produces = "application/yaml")
    @Operation(
            summary = "Cluster-agent install manifest (agent-led registration)",
            description = "Cluster 등록 직후 사용자가 자신의 kubectl context 에서 직접 apply 할 수 있는 "
                    + "ready-to-apply YAML. JWT token + backend gRPC endpoint 가 baked in. "
                    + "예: `curl -sS .../agent-manifest.yaml | kubectl apply -f -`. "
                    + "Token 은 cluster registration 시점에 발급된 한 번뿐인 단기 JWT — 본 endpoint 는 매 호출 "
                    + "새 token 을 발급 (Bruno 등 GUI 가 등록 후 별도 fetch 시 재발급 가능).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "manifest YAML (application/yaml)"),
        @ApiResponse(responseCode = "404", description = "cluster not found"),
        @ApiResponse(responseCode = "410", description = "cluster 가 이미 ACTIVE — 재발급 원하면 admin reinstall endpoint")
    })
    public ResponseEntity<String> agentManifestYaml(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        // 매 호출 새 token 발급. 운영에서는 token reuse 회피 + GUI/CLI 가 등록 후
        // 시간차 fetch 해도 동작하도록 함. 보안적으로 cluster registration endpoint 가 인증되면
        // 본 endpoint 도 동일 권한.
        BootstrapInfo bootstrap = agentApiManagedInstaller.prepareBootstrap(clusterName);
        String manifest = agentApiManagedInstaller.renderManifest(clusterName, bootstrap.token());
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"agent-manifest.yaml\"")
                .body(manifest);
    }

    private static Map<String, String> clusterLinks(String name) {
        return Map.of(
                "self", "/v1/clusters/" + name,
                "operations", "/v1/clusters/" + name + "/operations",
                "helmReleases", "/v1/clusters/" + name + "/helm-releases",
                "namespaces", "/v1/clusters/" + name + "/namespaces",
                "events", "/v1/clusters/" + name + "/events",
                "agentManifest", "/v1/clusters/" + name + "/agent-manifest.yaml");
    }
}
