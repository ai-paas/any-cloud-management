package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.CreateRequest;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.DebugPodResult;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.NodeDebugPodException;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssueRequest;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssuedKubeconfig;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster 직접 접근을 위한 보조 endpoint.
 *
 * <ul>
 * <li>GET /v1/clusters/{c}/kubeconfig?serviceAccount=&namespace=&ttlSeconds= —
 * kubeconfig YAML
 * 다운로드 (agent 단기 SA token). identity 는 KubeconfigIdentityResolver 가 결정 —
 * VM(PULUMI)
 * cluster 는 미지정 시 admin SA 기본, 그 외는 SA 명시. 단일 발급 엔드포인트.</li>
 * <li>POST /v1/clusters/{c}/nodes/{node}/debug-pod — host namespace +
 * privileged debug pod 생성.
 * 응답으로 (namespace, pod_name) 반환. Frontend 가 그 정보로
 * /v1/clusters/{c}/pods/{ns}/{pod}/exec
 * WebSocket 으로 연결 (PodExec 재사용).</li>
 * </ul>
 *
 * <p>
 * 두 endpoint 모두 cluster 측 RBAC + agent AllowList 통과 필수.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cluster Access (v1)", description = "Kubeconfig 발급 + node debug shell")
public class ClusterAccessController {

    private static final String CLUSTER_REGEXP = ApiValidationConstants.K8S_NAME_PATTERN;
    private static final int CLUSTER_MAX = ApiValidationConstants.K8S_NAME_MAX;

    private final KubeconfigExportService kubeconfigExportService;
    private final NodeDebugPodService nodeDebugPodService;
    private final com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService vmClusterSshAccessService;
    // kubeconfig 발급 identity(ns+SA) 결정의 단일 seam (VM admin 기본값 / 명시 SA / 향후
    // per-user).
    private final com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver kubeconfigIdentityResolver;

    // ===== Kubeconfig =====

    @GetMapping(path = "/clusters/{clusterName}/kubeconfig", produces = "application/yaml")
    @Operation(
            summary = "kubeconfig YAML 다운로드 (단기 SA token)",
            description = "agent 가 K8s TokenRequest API 로 ServiceAccount 의 한시 token 을 발급한 뒤 표준 "
                    + "kubeconfig YAML 을 합성해 다운로드한다 (정적 admin 자격 미보관). 발급 identity 는 "
                    + "KubeconfigIdentityResolver 가 결정: serviceAccount 명시 시 그대로, 미지정 + VM(PULUMI) "
                    + "cluster 면 자동 생성 cluster-admin SA(기본 aipaas-admin / aipaas-system) 로 전체 권한 다운로드. "
                    + "registered/BYO 는 존재하는 serviceAccount(+namespace) 명시 필요 (미지정 시 400). "
                    + "단일 발급 엔드포인트 — 이전 POST /kubeconfig 는 본 GET 으로 통합됐다.")
    public ResponseEntity<String> downloadKubeconfig(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(name = "serviceAccount", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String serviceAccount,
            @RequestParam(name = "namespace", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String namespace,
            @RequestParam(name = "ttlSeconds", required = false) Long ttlSeconds) {
        // identity(ns+SA) 해석은 KubeconfigIdentityResolver 단일 seam — VM admin 기본 / 명시 SA
        // / 향후 per-user.
        var identity = kubeconfigIdentityResolver.resolve(clusterName, serviceAccount, namespace);
        IssuedKubeconfig result = kubeconfigExportService.issue(
                clusterName,
                new IssueRequest(
                        identity.namespace(),
                        identity.serviceAccount(),
                        ttlSeconds,
                        clusterName,
                        identity.namespace()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/yaml"));
        headers.setContentDispositionFormData(
                "attachment", clusterName + "-" + identity.serviceAccount() + "-kubeconfig.yaml");
        return new ResponseEntity<>(result.kubeconfigYaml(), headers, HttpStatus.OK);
    }

    // ===== Node Debug Pod =====

    @PostMapping("/clusters/{clusterName}/nodes/{nodeName}/debug-pod")
    @Operation(
            summary = "Node debug shell 위한 임시 priviledged pod 생성",
            description = "kubectl debug node 등가. host PID/Net/IPC + privileged + nsenter 1 -- bash. "
                    + "응답의 (namespace, pod_name) 으로 /v1/clusters/{c}/pods/{ns}/{pod}/exec WebSocket 연결.")
    public ResponseEntity<ApiSuccessResponse<DebugPodResult>> createDebugPod(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank String nodeName,
            @RequestBody(required = false) DebugPodBody body) {
        CreateRequest req =
                body == null ? new CreateRequest(nodeName, null, null, null, null) : body.toRequest(nodeName);
        DebugPodResult result = nodeDebugPodService.create(clusterName, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.of(HttpStatus.CREATED.value(), "debug pod created", result));
    }

    @Schema(description = "node debug pod 생성 옵션")
    public record DebugPodBody(
            @Schema(description = "pod 가 생성될 namespace. default kube-system.") String namespace,
            @Schema(description = "container image. default agnhost (nsenter 포함).") String image,
            @Schema(description = "pod 이름. default aipaas-node-debug-{ts}.") String podName,
            @Schema(description = "TTL annotation. default 1800.") Long ttlSeconds) {

        CreateRequest toRequest(String nodeName) {
            return new CreateRequest(nodeName, namespace, image, podName, ttlSeconds);
        }
    }

    // ===== VM SSH access (Deprecated alias — canonical 은 /v1/vms/{name}/ssh-key)
    // =====

    @PostMapping("/clusters/{clusterName}/ssh-key")
    @Operation(
            summary = "VM cluster SSH private key 발급",
            description = "Pulumi 가 cluster 생성 시 만든 SSH keypair 의 private key (PEM) 를 state 에서 "
                    + "복호화해 반환 — 노드 직접 접속용. format=pem 이면 .pem 파일 다운로드, 기본 json 은 "
                    + "key + 노드별 ssh 명령 포함. CSP 무관 동일 동작. "
                    + "⚠ key 소지 = 전 노드 root 등가 — 전달/보관 주의.")
    public ResponseEntity<?> issueSshKey(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(name = "format", defaultValue = "json") String format) {
        var result = vmClusterSshAccessService.issueSshKey(clusterName);
        if ("pem".equalsIgnoreCase(format)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.setContentDispositionFormData("attachment", clusterName + "-ssh.pem");
            return new ResponseEntity<>(result.privateKeyPem(), headers, HttpStatus.OK);
        }
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "SSH key issued", result));
    }

    // ===== Error mapping =====
    // 에러는 전 API 공통의 ErrorResponse (RFC 9457) 한 가지 형태로 — 기존엔
    // ApiSuccessResponse(success=false) 로 내려 frontend 가 에러 파싱을 두 벌 유지해야 했다.
    // 원래 module 별 errorCode 문자열은 metadata.sourceCode 로 보존.

    @ExceptionHandler(
            com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService.VmClusterSshAccessException.class)
    public ResponseEntity<com.aipaas.anycloud.common.error.handler.ErrorResponse> handleSshAccessFailure(
            com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService.VmClusterSshAccessException e) {
        com.aipaas.anycloud.common.error.enums.ErrorCode code =
                switch (e.errorCode()) {
                    case "OUTPUTS_NOT_READY", "NO_STACK" -> com.aipaas.anycloud.common.error.enums.ErrorCode
                            .STATE_CONFLICT;
                    case "KEY_NOT_FOUND" -> com.aipaas.anycloud.common.error.enums.ErrorCode.NOT_FOUND;
                    default -> com.aipaas.anycloud.common.error.enums.ErrorCode.UPSTREAM_FAILED;
                };
        var body = com.aipaas.anycloud.common.error.handler.ErrorResponse.of(
                        code, e.getMessage(), Map.<String, Object>of("sourceCode", e.errorCode()))
                .withHint(
                        "OUTPUTS_NOT_READY".equals(e.errorCode())
                                ? "PROVISION 완료 후 재시도하세요 — GET /v1/clusters/{name} 의 status 확인."
                                : null);
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler({KubeconfigExportException.class, NodeDebugPodException.class})
    public ResponseEntity<com.aipaas.anycloud.common.error.handler.ErrorResponse> handleAccessFailures(
            RuntimeException e) {
        String sourceCode =
                (e instanceof KubeconfigExportException k) ? k.errorCode() : ((NodeDebugPodException) e).errorCode();
        com.aipaas.anycloud.common.error.enums.ErrorCode code =
                switch (sourceCode) {
                    case "NO_ACTIVE_AGENT" -> com.aipaas.anycloud.common.error.enums.ErrorCode.AGENT_UNAVAILABLE;
                    case "TIMEOUT" -> com.aipaas.anycloud.common.error.enums.ErrorCode.CLUSTER_CONNECTION_FAILED;
                    case "SERVICE_ACCOUNT_NOT_FOUND" -> com.aipaas.anycloud.common.error.enums.ErrorCode.NOT_FOUND;
                    case "NAMESPACE_NOT_ALLOWED", "COMMAND_NOT_ALLOWED" -> com.aipaas.anycloud.common.error.enums
                            .ErrorCode.FORBIDDEN;
                    case "MISSING_PARAM", "SERVICE_ACCOUNT_REQUIRED" -> com.aipaas.anycloud.common.error.enums.ErrorCode
                            .INVALID_INPUT_VALUE;
                    default -> com.aipaas.anycloud.common.error.enums.ErrorCode.UPSTREAM_FAILED;
                };
        var body = com.aipaas.anycloud.common.error.handler.ErrorResponse.of(
                        code, e.getMessage(), Map.<String, Object>of("sourceCode", sourceCode))
                .withHint(
                        "NO_ACTIVE_AGENT".equals(sourceCode)
                                ? "agent 설치/상태 확인: GET /v1/clusters/{name} 의 agentConnectivity, 설치는 GET /v1/clusters/{name}/agent-manifest.yaml."
                                : null);
        return ResponseEntity.status(code.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
