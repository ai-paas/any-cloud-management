package com.aipaas.anycloud.domain.kube.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.cluster.AgentBootstrapKubeClient;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.domain.kube.Namespaces;
import com.aipaas.anycloud.domain.kube.PagedKubeResourceResponse;
import com.aipaas.anycloud.domain.kube.internal.KubeErrorClassifier.DegradedInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aipaas.cluster.agent.runtime.KubeResourcePage;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import io.aipaas.cluster.agent.runtime.ResourceKindInfo;
import io.aipaas.cluster.agent.runtime.UnsupportedKindException;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <pre>
 * ClassName : KubeServiceImpl
 * Type : class
 * Description : 쿠버네티스 리소스 조회·삭제 day-2 서비스. 이후 모든 ops 는
 *               {@link KubeResourceService} (cluster agent) 로 routing — backend 는 fabric8
 *               client 를 보유하지 않는다.
 * Related : KubeResourceService (starter)
 * </pre>
 */
/**
 * KubeService 진입점. 책임 분리 — 같은 클래스 안에 다음 group 이 공존하며 향후 별도 service 로
 * 추출 가능:
 *
 * <ol>
 * <li>Day-2 ops routing — {@code kubeResourceService} (agent stream). 모든
 * list/get/delete 가
 * 이 path 만 사용.</li>
 * <li>Bootstrap-only fabric8 fallback — {@code bootstrapKubeClient} +
 * {@code clusterRepository}.
 * cluster 등록 직후 agent 미존재 chicken-and-egg 해소 전용.</li>
 * <li>Error classification + degraded metric — {@code degradedReasonCounter} +
 * private classifier
 * methods. 향후 {@code KubeErrorClassifier} 로 추출 가능.</li>
 * <li>Response sanitization — private {@code sanitize*} methods.
 * {@code service/kube/support/}
 * 의 helper 와 응집도 확보 위해 향후 이동 가능.</li>
 * </ol>
 */
@Slf4j
@Service("kubeServiceImpl")
@RequiredArgsConstructor
public class KubeServiceImpl implements KubeService {

    /** runbook §6 metric. */
    private final ObjectMapper objectMapper;
    /**
     * Day-2 K8s ops 를 agent stream 으로 routing — starter 측 facade. 부터 agent-only
     * path.
     */
    private final KubeResourceService kubeResourceService;
    /**
     * Bootstrap-only fabric8 client cache — cluster 등록 직후 agent 미존재 시
     * chicken-and-egg 해소.
     */
    private final AgentBootstrapKubeClient bootstrapKubeClient;
    /**
     * ClusterEntity 의 kubeconfig 조회 — bootstrap path 전용. day-2 ops 는 entity 접근 안 함.
     */
    private final ClusterRepository clusterRepository;

    private final KubeDegradedMetricsRecorder degradedMetricsRecorder;

    /**
     * Secret.data / stringData redact toggle.wildcard read RBAC 트레이드오프
     * mitigation. default ON — production 환경에서 secret base64 value 가 backend
     * response 에 노출되지
     * 않게. compliance 가 secret value 표시를 명시적으로 허용하는 환경에선 false 로 비활성.
     */
    @org.springframework.beans.factory.annotation.Value("${security.kube.redact-secrets:true}")
    private boolean redactSecrets;

    /** log marker — day-2 = AGENT, install-time bootstrap fallback = BOOTSTRAP. */
    private static final String SRC_AGENT = "AGENT";

    private static final String SRC_BOOTSTRAP = "BOOTSTRAP";

    /**
     * managedFields strip + (optional) Secret redact 의 단일 진입점.
     * 모든 agent path / bootstrap path 의 응답이 본 helper 를 통과해 일관 sanitize.
     */
    private JsonNode sanitize(JsonNode node) {
        JsonNode stripped = K8sResponseSanitizer.stripManagedFields(node);
        if (redactSecrets) {
            K8sResponseSanitizer.redactSecretValues(stripped);
        }
        return stripped;
    }

    /**
     * Day-2 agent path 실패 시 항상 {@link ErrorCode#AGENT_UNAVAILABLE} 로 변환.
     *
     * <p>
     * fabric8 fall-through 제거 — 모든 day-2 path 가 agent-only.
     * {@code KubeResourceService.isActiveFor=false} 또는 {@link KubeRoutingException}
     * →
     * 503 AGENT_UNAVAILABLE 즉시. caller (UI) 는 cluster degraded 알림/재시도 결정.
     */
    private CustomException agentUnavailable(String operation, String clusterName, Throwable cause) {
        String msg = "Cluster agent unavailable for " + operation + " on " + clusterName
                + (cause == null ? "" : ": " + cause.getMessage());
        log.error(
                "Agent routing for {} failed (cluster={}): {}",
                operation,
                clusterName,
                cause == null ? "no active session" : cause.getMessage());
        CustomException ex = new CustomException(msg, ErrorCode.AGENT_UNAVAILABLE);
        // cause chain 보존 — classifyDegradedCause 가 root cause 까지 unwrap 해 FORBIDDEN /
        // RESOURCE_KIND_DENIED 등 정확한 라벨 산출. CustomException ctor 가 cause 미수용 →
        // Throwable.initCause 사용.
        if (cause != null) {
            ex.initCause(cause);
        }
        return ex;
    }

    /** Day-2 agent path 가 활성인지 확인 — 미활성이면 즉시 {@link CustomException} throw. */
    private void requireAgent(String operation, String clusterName) {
        if (!kubeResourceService.isActiveFor(clusterName)) {
            throw agentUnavailable(operation, clusterName, null);
        }
    }

    /**
     * 지정 kind 의 자원 목록을 한 번에 모두 반환 — agent-only.
     *
     * <p>
     * legacy 경로 (fabric8 + {@link ResourceType} dispatcher) 제거. agent 의
     * {@code LIST_RESOURCES} 를 큰 limit 으로 호출해 동일 결과 산출. caller (legacy /v1
     * endpoint)
     * 의 페이지 미사용 시그니처를 유지하면서 day-2 path 통합.
     *
     * <p>
     * CircuitBreaker fallback 은 빈 배열 — UI 일부 위젯이 5xx 로 무너지지 않도록 부분 가용성.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @CircuitBreaker(name = "kubernetes", fallbackMethod = "getResourcesFallback")
    @Retry(name = "kubernetes")
    public JsonNode getResources(String clusterName, String namespace, String kind) {
        String ns = (namespace == null || namespace.trim().isEmpty()) ? "default" : namespace;
        log.info("Getting resources for cluster: {}, namespace: {}, kind: {}", clusterName, ns, kind);
        requireAgent("getResources", clusterName);
        try {
            // Non-paginated semantics: agent 의 paginated LIST_RESOURCES 를 단일 호출로 활용
            // (limit 500 = clampLimit 의 max). 운영상 한 page 안에 들어오는 케이스만 가정.
            // 더 큰 페이지가 필요하면 caller 가 listResourcesPaginated 로 직접 페이징필요.
            KubeResourcePage page =
                    kubeResourceService.listResourcesPaginated(clusterName, ns, normalizeKind(kind), 500, null, null);
            JsonNode items = sanitize(page.items());
            log.info("Retrieved {} resources of type {} (source={})", page.returnedCount(), kind, SRC_AGENT);
            return items;
        } catch (KubeRoutingException e) {
            throw agentUnavailable("getResources", clusterName, e);
        }
    }

    @CircuitBreaker(name = "kubernetes", fallbackMethod = "getResourceFallback")
    @Retry(name = "kubernetes")
    public JsonNode getResource(String clusterName, String namespace, String kind, String name) {
        String ns = (namespace == null || namespace.trim().isEmpty()) ? "default" : namespace;
        requireAgent("getResource", clusterName);
        try {
            JsonNode result = kubeResourceService.getResource(clusterName, ns, kind, name);
            log.info(
                    "getResource OK (source={}): cluster={}, kind={}, name={}, ns={}",
                    SRC_AGENT,
                    clusterName,
                    kind,
                    name,
                    ns);
            return sanitize(result);
        } catch (KubeRoutingException e) {
            throw agentUnavailable("getResource", clusterName, e);
        }
    }

    /**
     * Manifest apply (server-side apply) — agent-only. multi-doc YAML / JSON 둘 다
     * agent 가
     * in-cluster 에서 parse + apply. backend 는 manifest 텍스트를 통과만 시킴.
     */
    @Override
    public JsonNode applyResource(String clusterName, String namespace, String manifest) {
        return applyResource(clusterName, namespace, manifest, false);
    }

    @Override
    public JsonNode applyResource(String clusterName, String namespace, String manifest, boolean dryRun) {
        if (manifest == null || manifest.isBlank()) {
            throw new IllegalArgumentException("manifest body is required");
        }
        final String defaultNs =
                (namespace == null || namespace.isBlank() || "-".equals(namespace) || "_all".equals(namespace))
                        ? null
                        : namespace;

        requireAgent("applyResource", clusterName);
        try {
            // force=true. backend 가 agent install / chart upgrade 등 권한 있는 운영
            // 도구 입장이라 ConfigMap 등이 kubectl edit 으로 수정된 적 있으면 ownership 회복 필요.
            // FieldManagerConflict (409) 방지. dryRun 은 server-side validate-only.
            JsonNode result = kubeResourceService.applyResource(clusterName, defaultNs, manifest, true, dryRun);
            log.info(
                    "applyResource OK (source={}, dryRun={}): cluster={}, ns={}, manifest_bytes={}",
                    SRC_AGENT,
                    dryRun,
                    clusterName,
                    defaultNs,
                    manifest.length());
            return sanitize(result);
        } catch (KubeRoutingException e) {
            throw agentUnavailable("applyResource", clusterName, e);
        }
    }

    /**
     * Bootstrap path — ClusterEntity 의 kubeconfig 로 fabric8 client 만들어 server-side
     * apply.
     *
     * <p>
     * 호출 시점: cluster 등록 직후. day-2 ops 는 절대 이걸 부르지 않는다.
     * {@link AgentApiManagedInstaller}
     * 가 {@link #applyResource} 가 AGENT_UNAVAILABLE 로 실패할 때만 fallback 으로 사용.
     *
     * <p>
     * fabric8 의 multi-doc YAML 지원으로 manifest 안의 모든 resource 를 한 번에 apply. 마지막
     * resource 의 K8s 표현을 반환 (호출자가 단일 결과만 필요).
     */
    @Override
    public JsonNode applyResourceViaBootstrap(String clusterName, String namespace, String manifest) {
        if (manifest == null || manifest.isBlank()) {
            throw new IllegalArgumentException("manifest body is required");
        }
        ClusterEntity cluster =
                clusterRepository.findById(clusterName).orElseThrow(() -> new ClusterNotFoundException(clusterName));

        return bootstrapKubeClient.execute(cluster, client -> {
            // server-side apply force=true. 기존에 kubectl edit / 다른 fieldManager 로
            // 수정된 필드가 있으면 default apply 가 FieldManagerConflict (409) 로 fail. anycloud 가
            // 권한 있는 운영 도구 입장에서 ownership 가져옴 (helm-style 동작).
            final PatchContext apply = new PatchContext.Builder()
                    .withPatchType(PatchType.SERVER_SIDE_APPLY)
                    .withFieldManager("anycloud-backend")
                    .withForce(true)
                    .build();

            // helm 의 --create-namespace 와 동등 — manifest 의 namespaced 리소스가 참조하는 namespace 가
            // 없으면 fabric8 가 PATCH 단계에서 404 로 즉시 fail. 호출 측에서 명시한 namespace 가 비어있지
            // 않으면 idempotent server-side apply 로 먼저 만들어둔다. 이미 존재하면 no-op.
            if (namespace != null && !namespace.isBlank()) {
                io.fabric8.kubernetes.api.model.Namespace ns = new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .endMetadata()
                        .build();
                client.resource(ns).patch(apply, ns);
                log.info("BOOTSTRAP namespace ensured: cluster={}, ns={}", clusterName, namespace);
            }

            List<HasMetadata> resources = client.load(
                            new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)))
                    .items();
            List<HasMetadata> applied = new ArrayList<>();
            for (HasMetadata r : resources) {
                // manifest 의 metadata.namespace 가 비어있고 cluster-scoped 가 아니면 namespace 인자 사용.
                if ((r.getMetadata().getNamespace() == null
                                || r.getMetadata().getNamespace().isBlank())
                        && namespace != null
                        && !namespace.isBlank()) {
                    r.getMetadata().setNamespace(namespace);
                }
                applied.add(client.resource(r).patch(apply, r));
            }
            log.info(
                    "applyResource OK (source={}): cluster={}, ns={}, manifest_bytes={}, resources={}",
                    SRC_BOOTSTRAP,
                    clusterName,
                    namespace,
                    manifest.length(),
                    applied.size());
            // 다중 자원 — list 로 반환. caller (Installer) 는 적용 성공 여부만 필요.
            return sanitize(objectMapper.valueToTree(applied));
        });
    }

    public boolean deleteResource(String clusterName, String namespace, String kind, String name) {
        String ns = (namespace == null || namespace.trim().isEmpty()) ? "default" : namespace;
        requireAgent("deleteResource", clusterName);
        try {
            boolean ok = kubeResourceService.deleteResource(clusterName, ns, kind, name);
            log.info(
                    "deleteResource OK (source={}): cluster={}, kind={}, name={}, ns={}, deleted={}",
                    SRC_AGENT,
                    clusterName,
                    kind,
                    name,
                    ns,
                    ok);
            return ok;
        } catch (KubeRoutingException e) {
            throw agentUnavailable("deleteResource", clusterName, e);
        }
    }

    // ============== Pod logs ==============

    /**
     * Pod log tail 최댓값 cap — 응답 메모리 / 네트워크 폭주 방지. fabric8 의 getLog() 가 전체 string
     * 을 메모리로 가져오므로 10000 줄 정도가 합리적 상한.
     */
    private static final int MAX_TAIL_LINES = 10000;

    private static final int DEFAULT_TAIL_LINES = 100;

    @CircuitBreaker(name = "kubernetes", fallbackMethod = "getPodLogsFallback")
    @Retry(name = "kubernetes")
    public String getPodLogs(
            String clusterName,
            String namespace,
            String podName,
            String container,
            Integer tailLines,
            boolean previous,
            Integer sinceSeconds) {
        String ns = Namespaces.defaultIfBlank(namespace);
        int lines = tailLines == null ? DEFAULT_TAIL_LINES : Math.min(Math.max(1, tailLines), MAX_TAIL_LINES);
        requireAgent("getPodLogs", clusterName);
        try {
            String result =
                    kubeResourceService.getPodLogs(clusterName, ns, podName, container, lines, previous, sinceSeconds);
            log.info(
                    "getPodLogs OK (source={}): cluster={}, pod={}, ns={}, bytes={}",
                    SRC_AGENT,
                    clusterName,
                    podName,
                    ns,
                    result == null ? 0 : result.length());
            return result;
        } catch (KubeRoutingException e) {
            throw agentUnavailable("getPodLogs", clusterName, e);
        }
    }

    @SuppressWarnings("unused")
    private String getPodLogsFallback(
            String clusterName,
            String namespace,
            String podName,
            String container,
            Integer tailLines,
            boolean previous,
            Integer sinceSeconds,
            Throwable t) {
        log.warn(
                "K8s circuit fallback (getPodLogs): cluster={}, ns={}, pod={}, cause={}",
                clusterName,
                namespace,
                podName,
                t.toString());
        return "[log unavailable — kubernetes circuit breaker open: " + t.getMessage() + "]";
    }

    // ============== Server-side pagination ==============

    /**
     * Pod 목록 페이지 조회 — agent-only. {@link #listResourcesPaginated} 의 pod 특화 wrapper.
     *
     * <p>
     * 동일 paginated agent path 로 통합. legacy endpoint 의 separate "pods" 시그니처
     * 만 유지 (호환성). 내부는 {@code listResourcesPaginated(kind="pods")} 와 동일 RPC.
     *
     * @throws CustomException {@code AGENT_UNAVAILABLE} agent session 없거나 호출 실패 시.
     */
    @CircuitBreaker(name = "kubernetes", fallbackMethod = "listPodsPaginatedFallback")
    @Retry(name = "kubernetes")
    public PagedKubeResourceResponse listPodsPaginated(
            String clusterName, String namespace, int limit, String continueToken, String labelSelector) {
        int safeLimit = clampLimit(limit);
        String ns = (namespace == null || namespace.isBlank()) ? null : namespace;
        requireAgent("listPodsPaginated", clusterName);
        try {
            KubeResourcePage page = kubeResourceService.listResourcesPaginated(
                    clusterName, ns, "pods", safeLimit, continueToken, labelSelector);
            JsonNode items = sanitize(page.items());
            log.info(
                    "Pod page loaded (source={}): cluster={}, ns={}, limit={}, returned={}, hasNext={}",
                    SRC_AGENT,
                    clusterName,
                    namespace,
                    safeLimit,
                    page.returnedCount(),
                    page.continueToken() != null && !page.continueToken().isBlank());
            return PagedKubeResourceResponse.builder()
                    .clusterName(clusterName)
                    .namespace(namespace)
                    .resourceType("pods")
                    .items(items)
                    .continueToken(page.continueToken())
                    .returnedItemCount(page.returnedCount())
                    .build();
        } catch (KubeRoutingException e) {
            throw agentUnavailable("listPodsPaginated", clusterName, e);
        }
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) return 50;
        return Math.min(requested, 500);
    }

    @Override
    public PagedKubeResourceResponse listResourcesPaginated(
            String clusterName, String namespace, String kind, int limit, String continueToken, String labelSelector) {
        return listResourcesPaginated(clusterName, namespace, kind, limit, continueToken, labelSelector, null);
    }

    @CircuitBreaker(name = "kubernetes", fallbackMethod = "listResourcesPaginatedFallback")
    @Retry(name = "kubernetes")
    @Override
    public PagedKubeResourceResponse listResourcesPaginated(
            String clusterName,
            String namespace,
            String kind,
            int limit,
            String continueToken,
            String labelSelector,
            String fieldSelector) {
        String normalizedKind = normalizeKind(kind);
        int safeLimit = clampLimit(limit);
        String ns = (namespace == null || namespace.isBlank()) ? null : namespace;
        requireAgent("listResourcesPaginated", clusterName);
        try {
            KubeResourcePage page = kubeResourceService.listResourcesPaginated(
                    clusterName,
                    ns,
                    normalizedKind,
                    safeLimit,
                    continueToken,
                    labelSelector,
                    fieldSelector == null ? "" : fieldSelector);
            JsonNode items = sanitize(page.items());
            log.info(
                    "Resource page loaded (source={}): cluster={}, ns={}, kind={}, limit={}, returned={}, hasNext={}, fs={}",
                    SRC_AGENT,
                    clusterName,
                    ns,
                    normalizedKind,
                    safeLimit,
                    page.returnedCount(),
                    page.continueToken() != null && !page.continueToken().isBlank(),
                    fieldSelector == null || fieldSelector.isBlank() ? "-" : fieldSelector);
            return PagedKubeResourceResponse.builder()
                    .clusterName(page.clusterName())
                    .namespace(page.namespace())
                    .resourceType(page.kind())
                    .items(items)
                    .continueToken(page.continueToken())
                    .returnedItemCount(page.returnedCount())
                    .build();
        } catch (KubeRoutingException e) {
            throw agentUnavailable("listResourcesPaginated", clusterName, e);
        }
    }

    @Override
    public PagedKubeResourceResponse listEventsFor(
            String clusterName, String namespace, String involvedKind, String involvedName) {
        String involvedKindCanonical = pluralToKind(involvedKind);
        StringBuilder fs = new StringBuilder("involvedObject.kind=")
                .append(involvedKindCanonical)
                .append(",involvedObject.name=")
                .append(involvedName);
        if (namespace != null && !namespace.isBlank() && !"_all".equals(namespace) && !"-".equals(namespace)) {
            fs.append(",involvedObject.namespace=").append(namespace);
        }
        return listResourcesPaginated(clusterName, namespace, "events", 200, null, null, fs.toString());
    }

    @Override
    public JsonNode restartResource(String clusterName, String namespace, String kind, String name) {
        String normalizedKind = normalizeKind(kind);
        if ("pods".equals(normalizedKind)) {
            boolean ok = deleteResource(clusterName, namespace, normalizedKind, name);
            ObjectNode res = objectMapper.createObjectNode();
            res.put("kind", "pods");
            res.put("name", name);
            res.put("deleted", ok);
            return res;
        }
        if (!RESTARTABLE_WORKLOADS.contains(normalizedKind)) {
            throw new IllegalArgumentException("restart not supported for kind: " + kind);
        }
        JsonNode current = getResource(clusterName, namespace, normalizedKind, name);
        if (current == null || current.isMissingNode() || current.isNull()) {
            throw new IllegalStateException("resource not found: " + kind + "/" + name);
        }
        ObjectNode root = ((ObjectNode) current).deepCopy();
        ObjectNode spec = ensureObject(root, "spec");
        ObjectNode template = ensureObject(spec, "template");
        ObjectNode tplMeta = ensureObject(template, "metadata");
        ObjectNode annotations = ensureObject(tplMeta, "annotations");
        annotations.put("kubectl.kubernetes.io/restartedAt", Instant.now().toString());
        return applyResource(clusterName, namespace, serializeForApply(root));
    }

    @Override
    public JsonNode scaleResource(String clusterName, String namespace, String kind, String name, int replicas) {
        String normalizedKind = normalizeKind(kind);
        if (!SCALABLE_WORKLOADS.contains(normalizedKind)) {
            throw new IllegalArgumentException("scale not supported for kind: " + kind);
        }
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        JsonNode current = getResource(clusterName, namespace, normalizedKind, name);
        if (current == null || current.isMissingNode() || current.isNull()) {
            throw new IllegalStateException("resource not found: " + kind + "/" + name);
        }
        ObjectNode root = ((ObjectNode) current).deepCopy();
        ObjectNode spec = ensureObject(root, "spec");
        spec.put("replicas", replicas);
        return applyResource(clusterName, namespace, serializeForApply(root));
    }

    private static final Set<String> RESTARTABLE_WORKLOADS = Set.of("deployments", "statefulsets", "daemonsets");

    private static final Set<String> SCALABLE_WORKLOADS = Set.of("deployments", "replicasets", "statefulsets");

    // K8s Event 의 involvedObject.kind 는 PascalCase singular ("Pod", "Deployment").
    // URL path 의
    // plural lowercase 를 정규화. 미매핑 plural 은 trailing -s strip + title-case fallback.
    private static final Map<String, String> PLURAL_TO_KIND = Map.ofEntries(
            Map.entry("pods", "Pod"),
            Map.entry("services", "Service"),
            Map.entry("deployments", "Deployment"),
            Map.entry("statefulsets", "StatefulSet"),
            Map.entry("daemonsets", "DaemonSet"),
            Map.entry("replicasets", "ReplicaSet"),
            Map.entry("configmaps", "ConfigMap"),
            Map.entry("config-maps", "ConfigMap"),
            Map.entry("secrets", "Secret"),
            Map.entry("namespaces", "Namespace"),
            Map.entry("nodes", "Node"),
            Map.entry("serviceaccounts", "ServiceAccount"),
            Map.entry("service-accounts", "ServiceAccount"),
            Map.entry("persistentvolumeclaims", "PersistentVolumeClaim"),
            Map.entry("persistentvolumes", "PersistentVolume"),
            Map.entry("jobs", "Job"),
            Map.entry("cronjobs", "CronJob"),
            Map.entry("ingresses", "Ingress"));

    private static String pluralToKind(String plural) {
        if (plural == null || plural.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        String norm = plural.toLowerCase(Locale.ROOT);
        String mapped = PLURAL_TO_KIND.get(norm);
        if (mapped != null) return mapped;
        // Fallback: trailing 's' strip + title-case ("widgets" → "Widget"). 미보장 — 호출자는
        // 알려진 kind 만 전달 가정.
        String singular = norm.endsWith("s") ? norm.substring(0, norm.length() - 1) : norm;
        if (singular.isEmpty()) {
            throw new IllegalArgumentException("unsupported plural for event filtering: " + plural);
        }
        return Character.toUpperCase(singular.charAt(0)) + singular.substring(1);
    }

    private ObjectNode ensureObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing instanceof ObjectNode existingObject) {
            return existingObject;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(field, created);
        return created;
    }

    // apply 전 server-managed 필드 strip. resource-edit-modal 의 stripVolatile 과 동일 정책.
    private String serializeForApply(ObjectNode root) {
        root.remove("status");
        if (root.get("metadata") instanceof ObjectNode metadata) {
            metadata.remove("uid");
            metadata.remove("resourceVersion");
            metadata.remove("selfLink");
            metadata.remove("creationTimestamp");
            metadata.remove("generation");
            metadata.remove("managedFields");
            metadata.remove("ownerReferences");
            metadata.remove("finalizers");
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize manifest for apply", e);
        }
    }

    /**
     * 호환성 alias —
     * {@link com.aipaas.anycloud.domain.kube.model.K8sKinds#CLUSTER_SCOPED} 가 단일
     * 진실 소스. 신규 코드는 K8sKinds 를 직접 import.
     */
    public static final java.util.Set<String> CLUSTER_SCOPED_KINDS =
            com.aipaas.anycloud.domain.kube.model.K8sKinds.CLUSTER_SCOPED;

    public static boolean isClusterScoped(String kind) {
        return com.aipaas.anycloud.domain.kube.model.K8sKinds.isClusterScoped(kind);
    }

    private static String normalizeKind(String kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        return kind.toLowerCase(java.util.Locale.ROOT);
    }

    // ============== Resilience4j fallback ==============
    // CircuitBreaker 가 OPEN 또는 retry 한도 초과 시 호출되는 경로. 시그니처는 원본 + 마지막에 Throwable.
    // ClusterNotFoundException 은 4xx 로 그대로 전파해야 하므로 분기 처리.

    @SuppressWarnings("unused")
    private JsonNode getResourcesFallback(String clusterName, String namespace, String kind, Throwable t) {
        if (t instanceof ClusterNotFoundException cnf) {
            throw cnf;
        }
        log.warn(
                "K8s circuit fallback (getResources): cluster={}, kind={}, ns={}, cause={}",
                clusterName,
                kind,
                namespace,
                t.toString());
        return objectMapper.valueToTree(Collections.emptyList());
    }

    @SuppressWarnings("unused")
    private JsonNode getResourceFallback(String clusterName, String namespace, String kind, String name, Throwable t) {
        if (t instanceof ClusterNotFoundException cnf) {
            throw cnf;
        }
        log.warn(
                "K8s circuit fallback (getResource): cluster={}, kind={}, name={}, cause={}",
                clusterName,
                kind,
                name,
                t.toString());
        return objectMapper.nullNode();
    }

    @SuppressWarnings("unused")
    private PagedKubeResourceResponse listPodsPaginatedFallback(
            String clusterName, String namespace, int limit, String continueToken, String labelSelector, Throwable t) {
        if (t instanceof ClusterNotFoundException cnf) {
            throw cnf;
        }
        log.warn(
                "K8s circuit fallback (listPodsPaginated): cluster={}, ns={}, cause={}",
                clusterName,
                namespace,
                t.toString());
        DegradedInfo deg = KubeErrorClassifier.classify(t);
        degradedMetricsRecorder.record(clusterName, "pods", deg.reason());
        return PagedKubeResourceResponse.builder()
                .clusterName(clusterName)
                .namespace(namespace)
                .resourceType("pods")
                .items(objectMapper.valueToTree(Collections.emptyList()))
                .continueToken(null)
                .returnedItemCount(0)
                .degraded(Boolean.TRUE)
                .degradedReason(deg.reason())
                .degradedMessage(deg.message())
                .build();
    }

    @SuppressWarnings("unused")
    private PagedKubeResourceResponse listResourcesPaginatedFallback(
            String clusterName,
            String namespace,
            String kind,
            int limit,
            String continueToken,
            String labelSelector,
            String fieldSelector,
            Throwable t) {
        if (t instanceof ClusterNotFoundException cnf) {
            throw cnf;
        }
        if (t instanceof IllegalArgumentException iae) {
            // 미지원 kind 는 4xx 로 전파해야 진단 가능. fallback 으로 가리지 않음.
            throw iae;
        }
        log.warn(
                "K8s circuit fallback (listResourcesPaginated): cluster={}, ns={}, kind={}, cause={}",
                clusterName,
                namespace,
                kind,
                t.toString());
        DegradedInfo deg = KubeErrorClassifier.classify(t);
        degradedMetricsRecorder.record(clusterName, kind, deg.reason());
        return PagedKubeResourceResponse.builder()
                .clusterName(clusterName)
                .namespace(namespace)
                .resourceType(kind == null ? "" : kind.toLowerCase(java.util.Locale.ROOT))
                .items(objectMapper.valueToTree(Collections.emptyList()))
                .continueToken(null)
                .returnedItemCount(0)
                .degraded(Boolean.TRUE)
                .degradedReason(deg.reason())
                .degradedMessage(deg.message())
                .build();
    }

    /**
     * runbook §6 monitoring. {@link KubeErrorClassifier#classify} 분류 결과를
     * (cluster, kind, reason) 라벨로 누적. FORBIDDEN 다발 cluster 자동 식별 가능. MeterRegistry
     * 미주입
     * 또는 등록 실패는 fail-open (audit 과 동일 정책).
     *
     * <p>
     * kind 카디널리티: GVR 식별 가능한 string 으로 normalize ({@code pods},
     * {@code applications.argoproj.io} 등).
     * null/blank 은 {@code unknown} 으로 displace.
     */
    @Override
    @CircuitBreaker(name = "kubernetes", fallbackMethod = "listResourceKindsFallback")
    @Retry(name = "kubernetes")
    public List<ResourceKindInfo> listResourceKinds(String clusterName) {
        requireAgent("listResourceKinds", clusterName);
        try {
            List<ResourceKindInfo> kinds = kubeResourceService.listResourceKinds(clusterName);
            log.info("listResourceKinds OK (source={}): cluster={}, count={}", SRC_AGENT, clusterName, kinds.size());
            return kinds;
        } catch (KubeRoutingException e) {
            throw agentUnavailable("listResourceKinds", clusterName, e);
        }
    }

    /**
     * CircuitBreaker fallback — kind list 가 비어 있어도 caller (UI) 가 default 12 kind 로
     * degrade 가능. 빈 list 반환 + log.warn.
     */
    @SuppressWarnings("unused")
    private List<ResourceKindInfo> listResourceKindsFallback(String clusterName, Throwable t) {
        log.warn(
                "K8s circuit fallback (listResourceKinds): cluster={}, cause={}",
                clusterName,
                t == null ? "unknown" : t.getMessage());
        return List.of();
    }

    @Override
    public ResolvedResource resolveResource(String clusterName, String input) {
        // CircuitBreaker / Retry 미적용 — UNSUPPORTED_KIND 는 정상 응답 (사용자 입력 오류),
        // retry 무의미. AGENT_UNAVAILABLE 만 즉시 throw.
        requireAgent("resolveResource", clusterName);
        try {
            ResolvedResource resolved = kubeResourceService.resolveResource(clusterName, input);
            log.debug(
                    "resolveResource OK (source={}): cluster={}, input={}, plural={}",
                    SRC_AGENT,
                    clusterName,
                    input,
                    resolved.plural());
            return resolved;
        } catch (UnsupportedKindException e) {
            // suggestions 포함 — controller 가 propagate 해서 404 + suggestions 매핑.
            throw e;
        } catch (KubeRoutingException e) {
            throw agentUnavailable("resolveResource", clusterName, e);
        }
    }
}
