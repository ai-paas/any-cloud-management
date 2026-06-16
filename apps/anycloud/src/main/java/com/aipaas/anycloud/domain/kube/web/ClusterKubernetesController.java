package com.aipaas.anycloud.domain.kube.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.kube.KindResolver;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.domain.kube.PagedKubeResourceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.logstream.LogStreamBridge;
import io.aipaas.cluster.agent.logstream.PodLogStreamService;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import io.aipaas.cluster.agent.v1.LogStreamRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Cluster 하위 K8s 리소스 자원. 계층이 URL path 에 명시 — cluster/{name}/namespaces/{ns}/{kind}/{name}.
 * <p>
 * 페이지네이션은 모든 list 응답에 적용 (pageSize + pageToken).
 * <p>
 * <b>Namespace path 값 컨벤션</b>:
 * <ul>
 *   <li>일반 namespace 이름 — 해당 namespace 로 한정.</li>
 *   <li>{@code _all} — 모든 namespace 조회 (all-namespaces sentinel).</li>
 *   <li>{@code -} — cluster-scoped kind (nodes, namespaces, persistentvolumes, storageclasses,
 *       customresourcedefinitions) 일 때 권장하는 K8s 컨벤션. cluster-scoped kind 의 경우 어떤
 *       값이 와도 서버에서 ns 를 무시한다.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/clusters/{clusterName}/namespaces/{namespace}")
@Tag(name = "Cluster Kubernetes Resources (v1)", description = "Cluster 의 K8s 리소스 — pods/services/deployments 등")
@Validated
public class ClusterKubernetesController {

    private final KubeService kubeService;
    // Agent-mediated pod log streaming (SSE). agent ACTIVE 일 때만 동작 — inactive 면 503.
    private final PodLogStreamService podLogStreamService;
    // hardcoded K8sKinds.CLUSTER_SCOPED drift 해결. agent RESOLVE_RESOURCE
    // + Caffeine cache 로 동적 namespaced 판정. CRD 자동 cover. fallback 으로 static set 유지.
    private final KindResolver kindResolver;

    @GetMapping("/{kind}")
    @Operation(
            summary = "리소스 list (server-side pagination)",
            description = "지원 kind: pods, services, deployments, statefulsets, daemonsets, replicasets, "
                    + "configmaps, secrets, persistentvolumeclaims, jobs, cronjobs (namespaced); "
                    + "nodes, namespaces, persistentvolumes, storageclasses, customresourcedefinitions "
                    + "(cluster-scoped — {namespace} path 값 무시, 권장 `-`).")
    public ResponseEntity<ApiSuccessResponse<PagedKubeResourceResponse>> list(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable @Pattern(regexp = ApiValidationConstants.K8S_KIND_PATTERN) String kind,
            @Parameter(description = "페이지 크기 (1..500, default 50)")
                    @RequestParam(name = "pageSize", required = false, defaultValue = "50")
                    @Min(1)
                    @Max(500)
                    int pageSize,
            @Parameter(description = "이전 응답의 nextPageToken") @RequestParam(name = "pageToken", required = false)
                    String pageToken,
            @Parameter(description = "K8s label selector (예: app=nginx,tier=web)")
                    @RequestParam(name = "labelSelector", required = false)
                    String labelSelector) {
        String ns = effectiveNamespace(clusterName, namespace, kind);
        PagedKubeResourceResponse page =
                kubeService.listResourcesPaginated(clusterName, ns, kind, pageSize, pageToken, labelSelector);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Resources loaded", page)
                .withPagedMeta(pageSize, page.getContinueToken(), null));
    }

    @GetMapping("/{kind}/{resourceName}")
    @Operation(summary = "리소스 단건 조회")
    public ResponseEntity<ApiSuccessResponse<JsonNode>> getOne(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable @Pattern(regexp = ApiValidationConstants.K8S_KIND_PATTERN) String kind,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String resourceName) {
        JsonNode resource = kubeService.getResource(
                clusterName, effectiveNamespace(clusterName, namespace, kind), kind, resourceName);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Resource loaded", resource));
    }

    /**
     * Manifest apply (kubectl apply 등가). Content-Type 으로 YAML / JSON 둘 다 허용.
     * <p>
     * path 의 {kind} 는 routing / validation 용 — 실제 적용은 manifest 의 kind 가 우선.
     * 멀티 doc (---) manifest 도 한 번에 적용 가능.
     */
    @PostMapping(
            path = "/{kind}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/yaml", "application/x-yaml", "text/yaml"})
    @Operation(
            summary = "리소스 생성/업데이트 (server-side apply)",
            description = "kubectl apply 와 동일. body 는 K8s manifest (YAML 또는 JSON). 멀티 doc 지원. "
                    + "metadata.namespace 가 비어 있으면 path 의 {namespace} 를 적용. "
                    + "dryRun=true 면 K8s API server 의 admission/validation 만 수행 (실제 변경 없음).")
    public ResponseEntity<ApiSuccessResponse<JsonNode>> apply(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable @Pattern(regexp = ApiValidationConstants.K8S_KIND_PATTERN) String kind,
            @Parameter(description = "true 면 server-side dry-run (validate only). 저장 전 검증 / 미리보기 용.")
                    @RequestParam(name = "dryRun", required = false, defaultValue = "false")
                    boolean dryRun,
            @RequestBody @NotBlank @Size(max = 5_000_000) String manifest) {
        JsonNode applied = kubeService.applyResource(
                clusterName, effectiveNamespace(clusterName, namespace, kind), manifest, dryRun);
        HttpStatus status = dryRun ? HttpStatus.OK : HttpStatus.CREATED;
        String message = dryRun ? "Resource validated (dry-run)" : "Resource applied";
        return ResponseEntity.status(status).body(ApiSuccessResponse.of(status.value(), message, applied));
    }

    @GetMapping(path = "/pods/{podName}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(
            summary = "Pod 로그 snapshot (kubectl logs 등가)",
            description = "지정 pod 의 container log 를 text/plain 으로 반환. tailLines, previous, "
                    + "sinceSeconds, container query 로 필터. Streaming follow 는 미지원 (별도 SSE endpoint 필요).")
    public ResponseEntity<String> getPodLogs(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String podName,
            @Parameter(description = "다중 container pod 의 경우 container 이름. 비우면 첫 container.")
                    @RequestParam(name = "container", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String container,
            @Parameter(description = "마지막 N 줄만 반환 (1..10000, default 100)")
                    @RequestParam(name = "tailLines", required = false)
                    @Min(1)
                    @Max(10000)
                    Integer tailLines,
            @Parameter(description = "true 면 crash 직전 container 의 이전 log (kubectl --previous)")
                    @RequestParam(name = "previous", required = false, defaultValue = "false")
                    boolean previous,
            @Parameter(description = "지정 시 N 초 이내 log 만 (kubectl --since-seconds)")
                    @RequestParam(name = "sinceSeconds", required = false)
                    @Min(1)
                    @Max(86400)
                    Integer sinceSeconds) {
        String ns = effectiveNamespace(clusterName, namespace, "pods");
        String logs = kubeService.getPodLogs(clusterName, ns, podName, container, tailLines, previous, sinceSeconds);
        // Pod logs 는 의도적으로 ApiSuccessResponse envelope 없이 raw text — terminal pipe
        // (예: `curl /v1/.../logs | grep ERROR`) 친화. 다른 모든 endpoint 는 JSON envelope 사용.
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(logs);
    }

    /** SSE 의 long-lived 연결 보호 timeout. follow=true 에서도 1시간 이상 가는 단일 stream 은 흔치 않음. */
    private static final long SSE_TIMEOUT_MS = 60 * 60 * 1000L;

    @GetMapping(path = "/pods/{podName}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Pod 로그 streaming (kubectl logs -f 등가, SSE)",
            description = "지정 pod 의 container log 를 SSE 로 chunk-by-chunk push. cluster agent ACTIVE "
                    + "필수 — inactive 면 503 AGENT_UNAVAILABLE. follow=true 면 tail -f 처럼 실시간 push, "
                    + "false 면 tail_lines 만큼 snapshot 전송 후 close. SSE event 이름 = 'log'.")
    public SseEmitter streamPodLogs(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String podName,
            @Parameter(description = "다중 container pod 의 경우 container 이름. 비우면 첫 container.")
                    @RequestParam(name = "container", required = false)
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String container,
            @Parameter(description = "초기 출력 라인 수 (1..10000, default 100)")
                    @RequestParam(name = "tailLines", required = false)
                    @Min(1)
                    @Max(10000)
                    Integer tailLines,
            @Parameter(description = "true 면 실시간 tail (kubectl logs -f). false 면 snapshot.")
                    @RequestParam(name = "follow", required = false, defaultValue = "true")
                    boolean follow,
            @Parameter(description = "지정 시 N 초 이내 log 만 (kubectl --since-seconds)")
                    @RequestParam(name = "sinceSeconds", required = false)
                    @Min(1)
                    @Max(86400)
                    Integer sinceSeconds,
            @Parameter(description = "RFC3339 prefix timestamp 포함 여부 (kubectl --timestamps)")
                    @RequestParam(name = "timestamps", required = false, defaultValue = "false")
                    boolean timestamps,
            @Parameter(description = "Agent 가 max N 초 후 강제 close (follow=true 보호). 0 = 10분 default.")
                    @RequestParam(name = "maxDurationSeconds", required = false)
                    @Min(0)
                    @Max(86400)
                    Integer maxDurationSeconds) {

        String ns = effectiveNamespace(clusterName, namespace, "pods");

        // agent active 가 아니면 즉시 fail (snapshot endpoint 와 의도적으로 다름 — streaming 은 agent-only).
        // 향후 fabric8 fallback streaming 도 가능하지만 그 작업은 별도 sprint.
        if (!podLogStreamService.isActiveFor(clusterName)) {
            log.warn(
                    "streamPodLogs: agent not active cluster={} — caller should retry or use snapshot endpoint",
                    clusterName);
            SseEmitter immediate = new SseEmitter(0L);
            immediate.completeWithError(new IllegalStateException("Cluster agent not active for cluster " + clusterName
                    + ". SSE log streaming requires agent ACTIVE."));
            return immediate;
        }

        LogStreamRequest req = LogStreamRequest.newBuilder()
                .setNamespace(ns)
                .setPod(podName)
                .setContainer(container == null ? "" : container)
                .setTailLines(tailLines == null ? 100 : tailLines)
                .setFollow(follow)
                .setSinceSeconds(sinceSeconds == null ? 0 : sinceSeconds)
                .setTimestamps(timestamps)
                .setMaxDurationSeconds(maxDurationSeconds == null ? 0 : maxDurationSeconds)
                .build();

        LogStreamBridge bridge = podLogStreamService.openStream(clusterName, req);
        if (bridge == null) {
            SseEmitter immediate = new SseEmitter(0L);
            immediate.completeWithError(
                    new IllegalStateException("Failed to open agent log stream for cluster " + clusterName));
            return immediate;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // SSE client disconnect / timeout / error → bridge cancel → agent close.
        emitter.onCompletion(() -> {
            log.debug("SSE log stream completed (client) cluster={} pod={}", clusterName, podName);
            bridge.cancelFromConsumer();
        });
        emitter.onTimeout(() -> {
            log.info("SSE log stream timeout cluster={} pod={}", clusterName, podName);
            bridge.cancelFromConsumer();
            emitter.complete();
        });
        emitter.onError(t -> {
            log.warn("SSE log stream error cluster={} pod={}: {}", clusterName, podName, t.toString());
            bridge.cancelFromConsumer();
        });

        // Agent → SSE 흐름.
        bridge.setCallbacks(new LogStreamBridge.Callbacks() {
            @Override
            public void onChunk(byte[] data, boolean stderr) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(stderr ? "log-stderr" : "log")
                            .data(new String(data, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    log.debug(
                            "SSE send failed (client disconnected?) cluster={} pod={}: {}",
                            clusterName,
                            podName,
                            e.toString());
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete() {
                log.info("Agent log stream completed cluster={} pod={}", clusterName, podName);
                emitter.complete();
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Agent log stream error cluster={} pod={}: {}", clusterName, podName, t.toString());
                emitter.completeWithError(t);
            }
        });

        return emitter;
    }

    @DeleteMapping("/{kind}/{resourceName}")
    @Operation(summary = "리소스 삭제")
    public ResponseEntity<ApiSuccessResponse<java.util.Map<String, Object>>> delete(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName,
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.NAMESPACE_PATTERN)
                    @Size(max = ApiValidationConstants.NAMESPACE_MAX)
                    String namespace,
            @PathVariable @Pattern(regexp = ApiValidationConstants.K8S_KIND_PATTERN) String kind,
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String resourceName) {
        boolean ok = kubeService.deleteResource(
                clusterName, effectiveNamespace(clusterName, namespace, kind), kind, resourceName);
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                ok ? "Resource deleted" : "Delete returned no items",
                java.util.Map.of("deleted", ok)));
    }

    /**
     * Path 의 {@code namespace} 값을 서비스 호출용 ns 로 정규화한다.
     *
     * <p>기존 {@code KubeServiceImpl.isClusterScoped(kind)} 의 hardcoded
     * set 을 {@link KindResolver} 로 교체. RESOLVE_RESOURCE 결과 (agent 가 K8s discovery 로 동적 산출)
     * 의 {@code namespaced} flag 를 사용 — CRD 까지 자동 cover. agent 실패 시 hardcoded fallback.
     *
     * <ol>
     *   <li>cluster-scoped kind (namespaced=false) → 항상 {@code null} (path 값 무시).</li>
     *   <li>{@code _all} 또는 {@code -} → {@code null} (all-namespaces).</li>
     *   <li>그 외 → 입력 그대로.</li>
     * </ol>
     */
    private String effectiveNamespace(String clusterName, String namespace, String kind) {
        ResolvedResource resolved = kindResolver.resolve(clusterName, kind);
        // resolved 가 null 은 사실상 없지만 (input/cluster blank 만 null), defense-in-depth.
        boolean namespaced = resolved == null || resolved.namespaced();
        if (!namespaced) {
            return null;
        }
        if ("_all".equals(namespace) || "-".equals(namespace)) {
            return null;
        }
        return namespace;
    }
}
