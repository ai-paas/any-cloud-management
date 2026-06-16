package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigParser;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * kubeconfig 파일 업로드 → 백엔드 파싱 → registered cluster 등록.
 * <p>
 * 사용자는 `~/.kube/config` 또는 다른 kubeconfig 파일을 그대로 업로드하면 백엔드가:
 * <ol>
 *   <li>YAML 파싱</li>
 *   <li>current-context 가 가리키는 cluster + user 추출</li>
 *   <li>apiServerUrl / serverCA / clientCA / clientKey / clientToken 자동 채움</li>
 *   <li>인증 정보 검증 (token 또는 mTLS 둘 중 하나 필수)</li>
 *   <li>ClusterService.createCluster 호출</li>
 * </ol>
 * <p>
 * Custom method 서브패스 (`/clusters/importKubeconfig`) — POST 의 의미는 "kubeconfig 로 cluster
 * 등록" 이라는 특수 동작이라 일반 POST /v1/clusters (source=registered, 개별 필드) 와 분리.
 * 콜론 `:importKubeconfig` 대신 colon-free 서브패스로 전환 — Boot3 라우팅/proxy 안전.
 *
 * <pre>
 * POST /v1/clusters/importKubeconfig
 * Content-Type: multipart/form-data
 * params: clusterName, provider, clusterType?, description?
 * files:  kubeconfigFile
 * → 201 Created + Operation (CREATE_CLUSTER, SUCCEEDED)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Cluster Kubeconfig Import (v1)", description = "kubeconfig 파일 업로드 → 자동 파싱 → registered cluster 등록")
@Validated
public class ClusterKubeconfigImportController {

    private final KubeconfigParser kubeconfigParser;
    private final ClusterService clusterService;
    private final OperationService operationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(path = "/clusters/importKubeconfig", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "kubeconfig 파일 업로드로 cluster 등록",
            description = "사용자는 ~/.kube/config 또는 동등한 kubeconfig 파일을 그대로 업로드. "
                    + "백엔드가 current-context 의 cluster/user 를 파싱하여 CreateClusterDto 의 6 필드를 자동 채움. "
                    + "기본적으로 등록 직후 K8s API 연결성 검증 (`validate=true`) — 실패해도 cluster 는 유지(soft warn). "
                    + "`strict=true` 이면 검증 실패 시 cluster 등록을 rollback 하고 Operation FAILED 반환. "
                    + "수동 입력 흐름이 필요한 케이스는 POST /v1/clusters body source=registered 사용.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "cluster 등록 완료 + Operation(SUCCEEDED). " + "resultPayload.connectivityCheck 에 검증 결과 포함."),
        @ApiResponse(responseCode = "400", description = "kubeconfig 파싱 실패 / current-context 없음 / 인증 정보 부족"),
        @ApiResponse(responseCode = "409", description = "같은 이름의 cluster 가 이미 등록됨"),
        @ApiResponse(
                responseCode = "422",
                description = "strict=true + 연결성 검증 실패 — cluster 등록은 rollback 되어 FAILED Operation 반환")
    })
    public ResponseEntity<ApiSuccessResponse<OperationResponse>> importKubeconfig(
            @Parameter(description = "등록할 cluster 이름 (사용자 부여)", required = true)
                    @RequestParam
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @Parameter(description = "provider (AWS / GCP / Azure / OpenStack / ...)", required = true)
                    @RequestParam
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.PROVIDER_PATTERN)
                    String provider,
            @Parameter(description = "clusterType (EKS / GKE / AKS / OpenStack / Self-managed 등). 미지정 시 \"Imported\".")
                    @RequestParam(required = false)
                    @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$")
                    String clusterType,
            @Parameter(description = "설명 (선택)")
                    @RequestParam(required = false)
                    @Size(max = ApiValidationConstants.DESCRIPTION_MAX)
                    String description,
            @Parameter(description = "등록 직후 K8s API 연결성 검증 수행 여부 (default true).")
                    @RequestParam(name = "validate", required = false, defaultValue = "true")
                    boolean validate,
            @Parameter(
                            description =
                                    "validate=true 일 때 연결성 검증 실패 시 cluster 등록을 rollback 할지. "
                                            + "default false — 실패해도 cluster 는 유지하고 Operation 은 SUCCEEDED + 결과에 connected=false 기록.")
                    @RequestParam(name = "strict", required = false, defaultValue = "false")
                    boolean strict,
            @Parameter(description = "kubeconfig 파일 (YAML 형식, multipart)", required = true) @RequestParam
                    MultipartFile kubeconfigFile) {

        OperationEntity op = operationService.start(
                OperationType.CREATE_CLUSTER,
                "cluster",
                clusterName,
                "{\"source\":\"kubeconfig-import\",\"validate\":" + validate + ",\"strict\":" + strict + "}",
                validate ? 2 : 1);
        boolean clusterCreated = false;
        try {
            byte[] bytes;
            try {
                bytes = kubeconfigFile.getBytes();
            } catch (IOException e) {
                throw new IllegalArgumentException("kubeconfigFile read failed: " + e.getMessage());
            }
            CreateClusterDto dto = kubeconfigParser.parse(bytes);
            dto.setClusterName(clusterName);
            dto.setClusterProvider(provider);
            dto.setClusterType(clusterType == null || clusterType.isBlank() ? "Imported" : clusterType);
            dto.setDescription(description);

            operationService.updateProgress(op.getId(), "parse-and-register", 0, 30);
            clusterService.createCluster(dto);
            clusterCreated = true;

            ConnectivityCheckResult check = runConnectivityCheck(clusterName, validate, op.getId());

            if (validate && strict && !check.connected()) {
                // strict rollback — 등록을 되돌리고 Operation FAILED.
                safeDeleteCluster(clusterName);
                operationService.fail(
                        op.getId(),
                        "Connectivity check failed (strict=true). Cluster registration rolled back. " + "lastError="
                                + check.error());
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .location(URI.create("/v1/operations/" + op.getId()))
                        .body(ApiSuccessResponse.of(
                                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                                "Kubeconfig import failed — connectivity check failed (strict)",
                                OperationResponse.from(
                                        operationService.findById(op.getId()).orElse(op))));
            }

            operationService.complete(op.getId(), buildResultPayload(validate, strict, check));
        } catch (Exception e) {
            if (clusterCreated) {
                safeDeleteCluster(clusterName);
            }
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/v1/operations/" + op.getId()))
                .body(ApiSuccessResponse.of(
                                HttpStatus.CREATED.value(),
                                "Cluster imported from kubeconfig",
                                OperationResponse.from(
                                        operationService.findById(op.getId()).orElse(op)))
                        .withLinks(Map.of(
                                "self", "/v1/operations/" + op.getId(),
                                "resource", "/v1/clusters/" + clusterName,
                                "connectivityCheck", "/v1/clusters/" + clusterName + "/connectivity-checks")));
    }

    /**
     * validate=true 면 K8s API 연결성 검증을 수행하고 결과 record 반환.
     * validate=false 면 검증을 건너뛰고 performed=false 결과 반환.
     */
    private ConnectivityCheckResult runConnectivityCheck(String clusterName, boolean validate, String operationId) {
        if (!validate) {
            return new ConnectivityCheckResult(false, false, null);
        }
        operationService.updateProgress(operationId, "connectivity-check", 1, 70);
        try {
            boolean ok = Boolean.TRUE.equals(clusterService.testClusterConnection(clusterName));
            return new ConnectivityCheckResult(true, ok, ok ? null : "testClusterConnection returned false");
        } catch (Exception ex) {
            log.warn("Connectivity check threw exception for cluster {}: {}", clusterName, ex.getMessage());
            return new ConnectivityCheckResult(true, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * strict rollback / exception path 에서 호출. 삭제 실패는 warn 으로만 남기고 호출자에 throw 하지 않는다
     * (이미 원래의 실패 사유가 더 중요하므로 secondary failure 로 덮지 않음).
     */
    private void safeDeleteCluster(String clusterName) {
        try {
            clusterService.deleteCluster(clusterName);
        } catch (Exception ex) {
            log.warn("Cluster rollback delete failed for {}: {}", clusterName, ex.getMessage());
        }
    }

    private String buildResultPayload(boolean validate, boolean strict, ConnectivityCheckResult check) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", "kubeconfig-import");
        ObjectNode checkNode = root.putObject("connectivityCheck");
        checkNode.put("performed", check.performed());
        checkNode.put("connected", check.connected());
        checkNode.put("strict", strict);
        checkNode.put("validate", validate);
        checkNode.put("checkedAt", java.time.Instant.now().toString());
        if (check.error() != null) {
            checkNode.put("error", check.error());
        }
        return root.toString();
    }

    /** 연결성 검증 결과 record. */
    private record ConnectivityCheckResult(boolean performed, boolean connected, String error) {}
}
