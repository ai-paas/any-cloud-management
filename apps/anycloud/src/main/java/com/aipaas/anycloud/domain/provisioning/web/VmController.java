package com.aipaas.anycloud.domain.provisioning.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterOperationRequest;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.api.request.PatchClusterRequest;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryQueryService;
import com.aipaas.anycloud.domain.provisioning.api.request.VmCreateRequest;
import com.aipaas.anycloud.domain.provisioning.api.request.VmPatchRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * VM 인프라 자원 전용 API. {@code /v1/clusters} 의 source=vm 변형을 별도 namespace 로 노출.
 *
 * <p>책임 — Pulumi 통한 CSP VM provision 라이프사이클: create / scale / destroy / state history /
 * SSH 키 발급 / kubeconfig 다운로드 / 노드 목록 조회.
 *
 * <p>K8s cluster 의 registered/agent-led 등록은 별도 {@code ClusterController} 에서 다룬다 —
 * 두 라이프사이클의 책임 분리를 명시적으로 표현.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/vms")
@Tag(name = "Vms (v1)", description = "VM 인프라 자원 — Pulumi provisioned")
@Validated
public class VmController {

    private static final String VM_NAME_PATTERN = ApiValidationConstants.K8S_NAME_PATTERN;
    private static final int VM_NAME_MAX = ApiValidationConstants.K8S_NAME_MAX;

    private final ClusterFacade clusterFacade;
    private final VmClusterQueryService vmClusterQueryService;
    private final OperationService operationService;
    private final VmClusterStateHistoryQueryService stateHistoryQueryService;
    private final VmClusterSshAccessService vmClusterSshAccessService;
    private final KubeconfigExportService kubeconfigExportService;
    private final KubeconfigIdentityResolver kubeconfigIdentityResolver;

    // =============== Collection ===============

    @GetMapping
    @Operation(summary = "VM list", description = "Pulumi provisioned VM 목록. provider/environment/status filter.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공 — VmClusterListItemResponse 배열"),
        @ApiResponse(responseCode = "400", description = "filter 정규식 위반")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<VmClusterListItemResponse>>> list(
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.PROVIDER_PATTERN) String provider,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.ENVIRONMENT_PATTERN)
                    String environment,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationConstants.STATUS_PATTERN) String status) {
        var items = vmClusterQueryService.listVmClusters(provider, environment, status);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "VMs loaded", PagedData.of(items)));
    }

    // =============== Item ===============

    @GetMapping("/{vmName}")
    @Operation(summary = "단일 VM 상세 조회 (workflow / stack outputs / 진행 상태)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공 — VmClusterStatusResponse"),
        @ApiResponse(responseCode = "400", description = "vmName 형식 위반"),
        @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<ApiSuccessResponse<VmClusterStatusResponse>> getOne(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName) {
        var dto = vmClusterQueryService.getVmClusterStatus(vmName);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "VM loaded", dto).withLinks(vmLinks(vmName)));
    }

    @PostMapping
    @Operation(
            summary = "VM 생성 (Pulumi provision)",
            description = "Pulumi 가 CSP VM 인프라를 생성하고 Kubernetes 를 자동 설치한다. 비동기 → 202 + Operation. "
                    + "K8s ClusterEntity 등록은 bootstrap 완료 후 cluster-agent self-register 로 자동.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "수락 — Location: /v1/operations/{id}"),
        @ApiResponse(responseCode = "400", description = "spec 필드 누락 / vmName 형식 위반"),
        @ApiResponse(responseCode = "409", description = "이름 충돌 또는 동일 active VM 존재")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "VM provision request — provider, region, credentialId 필수.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = VmCreateRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "VM (AWS) provision",
                                                        value =
                                                                """
												{
												  "vmGroupName": "demo-aws-01",
												  "provider": "aws",
												  "region": "ap-northeast-2",
												  "environment": "dev",
												  "credentialId": "cred-aws-001",
												  "config": {
												    "workerCount": "3",
												    "instanceType": "t3.medium"
												  }
												}""")
                                            }))
                    @Valid
                    @RequestBody
                    VmCreateRequest request) {
        var op = clusterFacade.createDomain(toLegacyCreateRequest(request));
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                                HttpStatus.ACCEPTED.value(), "VM provision accepted", OperationResponse.from(op))
                        .withLinks(Map.of(
                                "self", "/v1/operations/" + op.id(),
                                "resource", "/v1/vms/" + request.getVmGroupName(),
                                "events", "/v1/operations/" + op.id() + "/events")));
    }

    @PatchMapping("/{vmName}")
    @Operation(summary = "VM scale (workerCount 변경)")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "변경 수락 — Operation"),
        @ApiResponse(responseCode = "400", description = "workerCount 범위 외 / spec 비어 있음"),
        @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> patch(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
            @Valid @RequestBody VmPatchRequest request) {
        var legacySpec = new PatchClusterRequest.Spec();
        if (request != null && request.getSpec() != null && request.getSpec().getWorkerCount() != null) {
            legacySpec.setWorkerCount(request.getSpec().getWorkerCount());
        }
        var legacy = new PatchClusterRequest();
        legacy.setSpec(legacySpec);
        var op = clusterFacade.patchDomain(vmName, legacy);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "VM update accepted", OperationResponse.from(op)));
    }

    @DeleteMapping("/{vmName}")
    @Operation(summary = "VM 삭제 (Pulumi destroy)")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "삭제 수락 — Operation"),
        @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> delete(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName) {
        var op = clusterFacade.deleteDomain(vmName);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "VM delete accepted", OperationResponse.from(op)));
    }

    // =============== Sub-resources ===============

    @PostMapping("/{vmName}/operations")
    @Operation(
            summary = "VM 액션 (retry workflow / retry registration / refresh)",
            description = "type=retryWorkflow|retryRegistration|refreshStatus.")
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> createOperation(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
            @Valid @RequestBody CreateClusterOperationRequest request) {
        var op = clusterFacade.createOperationDomain(vmName, request.getType().name());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/operations/" + op.id()))
                .body(ApiSuccessResponse.of(
                        HttpStatus.ACCEPTED.value(), "Operation accepted", OperationResponse.from(op)));
    }

    @GetMapping("/{vmName}/operations")
    @Operation(summary = "이 VM 의 operation 이력 (최신 순)")
    public ResponseEntity<ApiSuccessResponse<PagedData<OperationResponse>>> listOperations(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
            @RequestParam(name = "pageSize", required = false, defaultValue = "50") @Min(1) @Max(500) int pageSize) {
        var items = operationService.listByResource("cluster", vmName, pageSize).stream()
                .map(OperationResponse::from)
                .toList();
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Operations loaded", PagedData.of(items)));
    }

    @GetMapping("/{vmName}/state-history")
    @Operation(summary = "이 VM 의 workflow state transition 이력")
    public ResponseEntity<
                    ApiSuccessResponse<PagedData<com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory>>>
            listStateHistory(
                    @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
                    @RequestParam(name = "pageSize", required = false, defaultValue = "50") @Min(1) @Max(500)
                            int pageSize) {
        var items = stateHistoryQueryService.listRecentDomain(vmName, pageSize);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "State history loaded", PagedData.of(items)));
    }

    @GetMapping("/{vmName}/nodes")
    @Operation(summary = "VM 노드 정보 조회", description = "Provision 된 VM 노드 목록 (role, publicIp, privateIp 등) + SSH 사용자.")
    public ResponseEntity<ApiSuccessResponse<VmClusterSshAccessService.NodeListResult>> listNodes(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName) {
        var result = vmClusterSshAccessService.listNodes(vmName);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "VM nodes loaded", result));
    }

    @PostMapping("/{vmName}/ssh-key")
    @Operation(
            summary = "VM SSH private key 발급",
            description = "Pulumi 가 VM 생성 시 만든 SSH keypair 의 private key (PEM). format=pem 이면 .pem 다운로드, "
                    + "기본 json 은 key + 노드별 ssh 명령 포함. ⚠ key 소지 = 전 노드 root 등가.")
    public ResponseEntity<?> issueSshKey(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
            @RequestParam(name = "format", defaultValue = "json") String format) {
        var result = vmClusterSshAccessService.issueSshKey(vmName);
        if ("pem".equalsIgnoreCase(format)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.setContentDispositionFormData("attachment", vmName + "-ssh.pem");
            return new ResponseEntity<>(result.privateKeyPem(), headers, HttpStatus.OK);
        }
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "SSH key issued", result));
    }

    @GetMapping(path = "/{vmName}/kubeconfig", produces = "application/yaml")
    @Operation(
            summary = "VM 의 kubeconfig YAML 다운로드 (단기 SA token)",
            description = "agent 의 admin SA 단기 token + 표준 kubeconfig YAML 합성. cluster registered 되기 전 "
                    + "(bootstrap 도중) VM 측 접근용. registered 후에는 /v1/clusters/{name}/kubeconfig 권장.")
    public ResponseEntity<String> downloadKubeconfig(
            @PathVariable @NotBlank @Pattern(regexp = VM_NAME_PATTERN) @Size(max = VM_NAME_MAX) String vmName,
            @RequestParam(name = "serviceAccount", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String serviceAccount,
            @RequestParam(name = "namespace", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String namespace,
            @RequestParam(name = "ttlSeconds", required = false) Long ttlSeconds) {
        var identity = kubeconfigIdentityResolver.resolve(vmName, serviceAccount, namespace);
        var result = kubeconfigExportService.issue(
                vmName,
                new KubeconfigExportService.IssueRequest(
                        identity.namespace(), identity.serviceAccount(), ttlSeconds, vmName, identity.namespace()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/yaml"));
        headers.setContentDispositionFormData(
                "attachment", vmName + "-" + identity.serviceAccount() + "-kubeconfig.yaml");
        return new ResponseEntity<>(result.kubeconfigYaml(), headers, HttpStatus.OK);
    }

    // =============== Internals ===============

    /**
     * VmCreateRequest → facade 가 받는 CreateClusterRequest (source=vm) 로 변환.
     * facade 가 source-별 분기를 제거하면 본 변환도 함께 제거.
     */
    private CreateClusterRequest toLegacyCreateRequest(VmCreateRequest request) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("provider", request.getProvider());
        spec.put("region", request.getRegion());
        if (request.getEnvironment() != null) spec.put("environment", request.getEnvironment());
        spec.put("credentialId", request.getCredentialId());
        if (request.getDescription() != null) spec.put("description", request.getDescription());
        if (request.getConfig() != null) spec.put("config", request.getConfig());
        if (request.getHasGpuNodes() != null) spec.put("hasGpuNodes", request.getHasGpuNodes());
        return CreateClusterRequest.builder()
                .source(CreateClusterRequest.Source.vm)
                .clusterName(request.getVmGroupName())
                .spec(spec)
                .build();
    }

    private static Map<String, String> vmLinks(String name) {
        return Map.of(
                "self", "/v1/vms/" + name,
                "operations", "/v1/vms/" + name + "/operations",
                "stateHistory", "/v1/vms/" + name + "/state-history",
                "nodes", "/v1/vms/" + name + "/nodes",
                "sshKey", "/v1/vms/" + name + "/ssh-key",
                "kubeconfig", "/v1/vms/" + name + "/kubeconfig");
    }
}
