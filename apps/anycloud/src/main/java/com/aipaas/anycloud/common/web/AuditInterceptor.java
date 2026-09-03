package com.aipaas.anycloud.common.web;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.domain.audit.AuditEntry;
import com.aipaas.anycloud.domain.audit.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Mutation HTTP 요청에 대해 자동으로 audit 로그 한 줄 기록. 회수 가능한 정보(method, path,
 * principal, requestId, status, duration) 만 capture. body payload 는 controller layer 가 직접
 * AuditLogger 를 호출하여 풍부한 요약 (action/resourceId) 을 보강가능.
 */
// anycloud.audit.enabled=false 이면 bean 자체 등록 안 됨 (slice 테스트 / 운영 측 개별 비활성).
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "anycloud.audit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AuditInterceptor implements HandlerInterceptor {

    private static final String START_NS = "anycloud.audit.startNs";
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditLogger auditLogger;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!MUTATION_METHODS.contains(request.getMethod())) {
            return true;
        }
        request.setAttribute(START_NS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!MUTATION_METHODS.contains(request.getMethod())) {
            return;
        }
        Long startNs = (Long) request.getAttribute(START_NS);
        long durationMs = startNs == null ? -1L : (System.nanoTime() - startNs) / 1_000_000L;

        // path / action 추정: HandlerMethod 가 있으면 controller#method 형태로 action 유도.
        String action = inferAction(handler, request);
        String resourceIdHint = inferResourceId(request);

        Map<String, String> mdc = LoggingMdc.snapshot();
        AuditEntry entry = AuditEntry.builder()
                .requestId(mdc.get(LoggingMdc.REQUEST_ID))
                .principal(resolvePrincipal(request))
                .clientIp(mdc.getOrDefault(LoggingMdc.CLIENT_IP, request.getRemoteAddr()))
                .httpMethod(request.getMethod())
                .path(request.getRequestURI())
                .action(action)
                .resourceType(inferResourceType(request))
                .resourceId(resourceIdHint)
                .statusCode(response.getStatus())
                .durationMs(durationMs)
                .errorMessage(ex == null ? null : ex.toString())
                .requestSummary(null)
                .build();
        auditLogger.record(entry);
    }

    /**
     * Controller class 의 logical 도메인 이름. Suffix "Controller", "V1" 등을 제거하여
     * 안정적인 action prefix 를 만든다.
     * 예) ClusterController → cluster, ClusterKubernetesController → clusterKubernetes,
     *     ClusterKubeconfigImportController → clusterKubeconfigImport,
     *     OperationController → operation.
     */
    static String inferAction(Object handler, HttpServletRequest request) {
        if (handler instanceof HandlerMethod hm) {
            String simple = hm.getBeanType().getSimpleName();
            String cls = stripSuffix(stripSuffix(simple, "Controller"), "V1");
            // 첫 글자 lower-case (camelCase 유지).
            if (!cls.isEmpty()) {
                cls = Character.toLowerCase(cls.charAt(0)) + cls.substring(1);
            }
            return cls + "." + hm.getMethod().getName();
        }
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static String stripSuffix(String s, String suffix) {
        return s != null && s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    /**
     * URI prefix → 리소스 타입. v1 path 만 인식.
     */
    static String inferResourceType(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return null;
        // 가장 구체적인 sub-resource 먼저 매칭.
        if (uri.startsWith("/v1/clusters/")) {
            // /v1/clusters/{c}/helm-releases/...
            if (uri.contains("/helm-releases")) return "helmRelease";
            // /v1/clusters/{c}/namespaces/{ns}/{kind}/...  (sub-resource — K8s)
            if (uri.contains("/namespaces/")) return "k8sResource";
            return "cluster";
        }
        if (uri.startsWith("/v1/clusters")) return "cluster"; // POST /v1/clusters, /clusters/importKubeconfig
        if (uri.startsWith("/v1/cluster-validations")) return "clusterValidation";
        if (uri.startsWith("/v1/operations")) return "operation";
        if (uri.startsWith("/v1/helm-repos")) return "helmRepo";
        if (uri.startsWith("/v1/audit-logs")) return "auditLog";
        if (uri.startsWith("/v1/credentials")) return "cspCredential";
        if (uri.startsWith("/v1/workflow")) return "workflow";
        if (uri.startsWith("/v1/oidc-group-bindings")) return "oidcGroupBinding";
        if (uri.startsWith("/v1/admin/")) return "admin";
        return null;
    }

    /**
     * URI path 에서 핵심 리소스 식별자 추정. 휴리스틱이므로 controller 가 더 정확한 값을
     * 알면 별도 AuditLogger 호출로 덮어쓰기 가능.
     * <p>
     * 처리 규칙:
     * <ol>
     *   <li>action-suffix 단어 (cancel, retry, flush, install-all, enqueue, importKubeconfig,
     *       rollback, operations 등) 가 끝에 오면 한 단계 위 segment 가 실제 ID
     *       (예: {@code /operations/op-1/cancel} → {@code op-1}). 단 그 위 segment 가 collection
     *       marker 면 collection-level custom method (예: {@code /clusters/importKubeconfig}) 라
     *       식별자가 없어 null 반환.</li>
     *   <li>그 외 마지막 segment 가 식별자.</li>
     *   <li>(legacy) 옛 {@code :verb} 콜론 경로가 남아 들어오면 콜론 앞부분만 사용 — 모든 라우트는
     *       colon-free 서브패스로 전환됐으나 방어적으로 유지.</li>
     * </ol>
     */
    static String inferResourceId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) return null;

        // (legacy) 옛 :verb 콜론 경로 방어 — 모든 라우트는 colon-free 로 전환됐으나 잔존 호출 대비
        // 콜론 앞부분만 사용.
        int colonIdx = uri.lastIndexOf(':');
        String mainPath = colonIdx >= 0 ? uri.substring(0, colonIdx) : uri;

        String[] parts = mainPath.split("/");
        String last = parts.length > 0 ? parts[parts.length - 1] : "";
        String prev = parts.length > 1 ? parts[parts.length - 2] : "";

        Set<String> collectionMarkers =
                Set.of("clusters", "cluster-validations", "operations", "helm-repos", "audit-logs");
        // 옛 `/v1/clusters:importKubeconfig` 같이 collection 이름 자체에 :verb 가 붙던 케이스 — 식별자 없음.
        if (collectionMarkers.contains(last) && colonIdx >= 0) {
            return null;
        }

        // 액션성 suffix (collection / sub-resource verb) — 한 단계 위 segment 가 실제 ID.
        // 콜론 custom-method(:cancel/:flush/:install-all/:enqueue/:importKubeconfig)를 colon-free
        // 서브패스로 전환하면서, 옛 콜론과 동일한 resourceId 추출을 위해 verb suffix 로 추가.
        Set<String> verbs = Set.of(
                "operations",
                "connectivity-checks",
                "revisions",
                "resources",
                "events",
                "namespaces",
                "helm-releases",
                "rollback",
                "retry",
                "cancel",
                "flush",
                "install-all",
                "enqueue",
                "importKubeconfig");
        if (verbs.contains(last) && !prev.isBlank()) {
            // prev 가 collection marker 면 collection-level custom method (예: /clusters/importKubeconfig)
            // — 특정 식별자 없음.
            return collectionMarkers.contains(prev) ? null : prev;
        }

        return last.isBlank() ? null : last;
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        // 우선순위: X-User-Id (gateway 가 검증된 사용자 식별자를 forward) → SecurityContext → anonymous.
        String fromGw = request.getHeader("X-User-Id");
        if (fromGw != null && !fromGw.isBlank()) return fromGw;
        // SecurityContext lookup 은 의도적으로 생략 — gateway 환경에선 header 가 표준 경로.
        String mdcPrincipal = MDC.get("principal");
        return mdcPrincipal != null ? mdcPrincipal : "anonymous";
    }
}
