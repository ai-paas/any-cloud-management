package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleApplyResult;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleCatalog;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleInstaller;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleSet;
import io.aipaas.cluster.agent.observability.core.AlertSilenceResult;
import io.aipaas.cluster.agent.observability.core.AlertsResult;
import io.aipaas.cluster.agent.observability.core.DashboardLocation;
import io.aipaas.cluster.agent.observability.core.MetricTargetsResult;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import io.aipaas.cluster.agent.observability.dashboard.DashboardLocator;
import io.aipaas.cluster.agent.observability.metrics.ClusterMetricsService;
import io.aipaas.cluster.agent.observability.metrics.MetricSample;
import io.aipaas.cluster.agent.observability.metrics.StandardQueries;
import io.aipaas.cluster.agent.observability.metrics.StandardQuery;
import io.aipaas.cluster.agent.observability.query.ObservabilityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

/**
 * Cluster Observability REST endpoint.
 *
 * <p>Cluster 내부 Prometheus/Alertmanager/Grafana 와의 모든 통신은 본 controller 가 cluster-agent
 * starter 의 reverse-tunnel 을 통해 routing — frontend 는 cluster API server 에 직접 접근하지 않음.
 *
 * <p>모든 응답은 {@link ApiSuccessResponse} 로 wrap. 실패는 {@link ObservabilityException} 의
 * errorCode 가 HTTP status 로 매핑된다.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "Observability (v1)", description = "Cluster Prometheus/Grafana 호출 (PromQL/install/dashboard)")
public class ObservabilityController {

    private static final String CLUSTER_REGEXP = ApiValidationConstants.K8S_NAME_PATTERN;
    private static final int CLUSTER_MAX = ApiValidationConstants.K8S_NAME_MAX;

    private final ObservabilityQueryService queryService;
    private final DashboardLocator dashboardLocator;
    private final ClusterMetricsService metricsService;
    private final AlertRuleCatalog alertRuleCatalog;
    private final AlertRuleInstaller alertRuleInstaller;
    private final ObjectMapper objectMapper;

    // monitoring stack 설치는 POST /v1/clusters/{c}/addons (cluster_addon + RabbitMQ workflow).

    // ---- PromQL queries ----

    /**
     * Prometheus HTTP API `/api/v1/query` raw passthrough.
     *
     * <p>응답은 ApiSuccessResponse wrapping 없이 Prometheus 응답 그대로 (`status`, `data`).
     * frontend 가 PromQL 작성해 직접 호출 — catalog template (deprecated) 불필요.
     *
     * <p>`query` 가 신규 표준 param 이지만 backward compat 위해 `promql` 도 alias 로 수용
     * (둘 다 비어있으면 400). `timeoutSeconds` 는 deprecated — `timeout="30s"` 로 대체.
     */
    @GetMapping("/clusters/{clusterName}/metrics/query")
    @Operation(
            summary = "Prometheus instant query (raw passthrough)",
            description = "Prometheus `/api/v1/query` 와 동일 contract. 응답: "
                    + "`{\"status\":\"success\",\"data\":{...}}` raw. "
                    + "param 'query' 권장, 'promql' 은 backward-compat alias.")
    public ResponseEntity<JsonNode> query(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "promql", required = false) String promql,
            @RequestParam(required = false) String time,
            @RequestParam(value = "timeout", required = false) String timeout,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "lookback_delta", required = false) String lookbackDelta,
            @RequestParam(value = "stats", required = false) String stats,
            @RequestParam(value = "timeoutSeconds", required = false) Long timeoutSeconds) {
        String q = pickQuery(query, promql);
        Duration t = resolveTimeout(timeout, timeoutSeconds);
        Map<String, String> extras = buildExtras(timeout, limit, lookbackDelta, stats);
        PromQLResult result = queryService.queryInstant(clusterName, q, time, t, extras);
        return ResponseEntity.ok(parseRaw(result.raw()));
    }

    /** Prometheus `/api/v1/query_range` raw passthrough. start/end/step 필수. */
    @GetMapping("/clusters/{clusterName}/metrics/query_range")
    @Operation(
            summary = "Prometheus range query (raw passthrough)",
            description = "Prometheus `/api/v1/query_range`. 응답은 Prometheus envelope 그대로 "
                    + "(`resultType=matrix`). param 'query' 권장, 'promql' 은 backward-compat alias.")
    public ResponseEntity<JsonNode> queryRange(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "promql", required = false) String promql,
            @RequestParam @NotBlank String start,
            @RequestParam @NotBlank String end,
            @RequestParam @NotBlank String step,
            @RequestParam(value = "timeout", required = false) String timeout,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "lookback_delta", required = false) String lookbackDelta,
            @RequestParam(value = "stats", required = false) String stats,
            @RequestParam(value = "timeoutSeconds", required = false) Long timeoutSeconds) {
        String q = pickQuery(query, promql);
        Duration t = resolveTimeout(timeout, timeoutSeconds);
        Map<String, String> extras = buildExtras(timeout, limit, lookbackDelta, stats);
        PromQLResult result = queryService.queryRange(clusterName, q, start, end, step, t, extras);
        return ResponseEntity.ok(parseRaw(result.raw()));
    }

    /** query OR promql alias 중 비어있지 않은 값 선택. 둘 다 blank 면 MISSING_QUERY 로 에러. */
    private static String pickQuery(String query, String promql) {
        if (query != null && !query.isBlank()) return query;
        if (promql != null && !promql.isBlank()) return promql;
        throw new ObservabilityException("MISSING_QUERY", "either 'query' or 'promql' parameter required");
    }

    /** 신규 timeout (string "30s") 우선, 없으면 deprecated timeoutSeconds. 둘 다 없으면 service default. */
    private static Duration resolveTimeout(String timeout, Long timeoutSeconds) {
        if (timeout != null && !timeout.isBlank()) {
            // Prometheus duration string 은 service 가 extraParams 으로 직접 처리 — Duration 은 RPC level.
            // 여기서는 단순히 timeoutSeconds 가 있으면 우선, 없으면 null (service default 사용).
        }
        return timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
    }

    /** Prometheus optional param 의 non-null entry 만 Map 으로 구성. */
    private static Map<String, String> buildExtras(String timeout, Integer limit, String lookbackDelta, String stats) {
        Map<String, String> extras = new LinkedHashMap<>();
        if (timeout != null && !timeout.isBlank()) extras.put("timeout", timeout);
        if (limit != null) extras.put("limit", String.valueOf(limit));
        if (lookbackDelta != null && !lookbackDelta.isBlank()) extras.put("lookback_delta", lookbackDelta);
        if (stats != null && !stats.isBlank()) extras.put("stats", stats);
        return extras;
    }

    /** PromQLResult.raw 를 JsonNode 로 파싱 — 실패시 BAD_GATEWAY 매핑. */
    private JsonNode parseRaw(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new ObservabilityException(
                    "AGENT_CALL_FAILED", "failed to parse Prometheus response: " + e.getMessage(), e);
        }
    }

    @GetMapping("/observability/aggregate")
    @Operation(
            summary = "전체 cluster PromQL fan-out",
            description = "ClusterCatalog 의 모든 cluster 에 병렬 쿼리. 응답은 cluster_name → result map.")
    public ResponseEntity<ApiSuccessResponse<Map<String, PromQLResult>>> aggregate(
            @RequestParam @NotBlank String promql, @RequestParam(required = false) Long perClusterTimeoutSeconds) {
        Duration timeout =
                perClusterTimeoutSeconds == null ? Duration.ofSeconds(8) : Duration.ofSeconds(perClusterTimeoutSeconds);
        Map<String, PromQLResult> result = queryService.queryAll(promql, timeout);
        return ok("PromQL fan-out", result);
    }

    // ---- Targets / Alerts ----

    @GetMapping("/clusters/{clusterName}/observability/targets")
    @Operation(
            summary = "Prometheus scrape target 상태",
            description = "state=active|dropped optional. raw 에 /api/v1/targets JSON.")
    public ResponseEntity<ApiSuccessResponse<MetricTargetsResult>> targets(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false) String state) {
        MetricTargetsResult result = queryService.listTargets(clusterName, state, null);
        return ok("scrape targets", result);
    }

    @GetMapping("/clusters/{clusterName}/observability/alerts")
    @Operation(summary = "Alertmanager 활성 alert 조회")
    public ResponseEntity<ApiSuccessResponse<AlertsResult>> alerts(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        AlertsResult result = queryService.listAlerts(clusterName, null);
        return ok("alerts", result);
    }

    // Alert silences (2) ----

    @GetMapping("/clusters/{clusterName}/observability/alert-silences")
    @Operation(
            summary = "Alertmanager silence 목록 조회",
            description = "Alertmanager /api/v2/silences GET. raw 필드에 silence array JSON 그대로.")
    public ResponseEntity<ApiSuccessResponse<AlertsResult>> listSilences(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        AlertsResult result = queryService.listAlertSilences(clusterName, null);
        return ok("alert silences", result);
    }

    @PostMapping("/clusters/{clusterName}/observability/alert-silences")
    @Operation(
            summary = "Alertmanager silence 생성",
            description = "matchers 는 Alertmanager schema 의 [{name,value,isRegex,isEqual}, ...] JSON array. "
                    + "startsAt/endsAt 은 RFC3339 timestamp (예: 2026-05-22T10:00:00Z).")
    public ResponseEntity<ApiSuccessResponse<AlertSilenceResult>> createSilence(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestBody CreateSilenceBody body) {
        AlertSilenceResult result = queryService.createAlertSilence(
                clusterName,
                body.matchers(),
                body.startsAt(),
                body.endsAt(),
                body.createdBy() == null ? "anycloud" : body.createdBy(),
                body.comment() == null ? "" : body.comment(),
                null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.of(HttpStatus.CREATED.value(), "silence created", result));
    }

    @DeleteMapping("/clusters/{clusterName}/observability/alert-silences/{silenceId}")
    @Operation(
            summary = "Alertmanager silence 삭제",
            description = "Alertmanager /api/v2/silence/{id} DELETE — silenceId 는 create 시 발급된 UUID.")
    public ResponseEntity<ApiSuccessResponse<Boolean>> deleteSilence(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 128) String silenceId) {
        boolean ok = queryService.deleteAlertSilence(clusterName, silenceId, null);
        return ok("silence deleted", ok);
    }

    /**
     * Silence 생성 request body.
     *
     * @param matchers  Alertmanager matcher JSON array (필수)
     * @param startsAt  RFC3339 시작 시각 (필수)
     * @param endsAt    RFC3339 종료 시각 (필수)
     * @param createdBy 생성자 식별 (optional, default=anycloud)
     * @param comment   설명 (optional)
     */
    public record CreateSilenceBody(
            String matchers, String startsAt, String endsAt, String createdBy, String comment) {}

    // Alert rule catalog (6) ----

    @GetMapping("/observability/alert-rules")
    @Operation(
            summary = "anycloud-default PrometheusRule 카탈로그",
            description = "starter 가 제공하는 표준 alert rule 목록. id 별로 install 가능.")
    public ResponseEntity<ApiSuccessResponse<List<AlertRuleSet>>> alertRuleCatalog() {
        return ok("alert rule catalog", alertRuleCatalog.list());
    }

    @PostMapping("/clusters/{clusterName}/observability/alert-rules/{ruleSetId}")
    @Operation(
            summary = "단일 alert rule-set 설치",
            description = "본 cluster 의 monitoring namespace 에 PrometheusRule CR 을 APPLY_MANIFEST. "
                    + "namespace/release query 가 없으면 monitoring / kube-prometheus-stack default.")
    public ResponseEntity<ApiSuccessResponse<AlertRuleApplyResult>> installRuleSet(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String ruleSetId,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String release) {
        AlertRuleApplyResult result = alertRuleInstaller.install(clusterName, ruleSetId, namespace, release, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.of(HttpStatus.CREATED.value(), "alert rule-set installed", result));
    }

    @PostMapping("/clusters/{clusterName}/observability/alert-rules/install-all")
    @Operation(
            summary = "카탈로그 전체 설치",
            description = "각 rule-set 별 결과 list 반환. 일부 실패해도 나머지 계속 — status='failed:<code>' 표시.")
    public ResponseEntity<ApiSuccessResponse<List<AlertRuleApplyResult>>> installAllRuleSets(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String release) {
        List<AlertRuleApplyResult> results = alertRuleInstaller.installAll(clusterName, namespace, release, null);
        return ok("alert rule-sets installed", results);
    }

    @DeleteMapping("/clusters/{clusterName}/observability/alert-rules/{ruleSetId}")
    @Operation(summary = "단일 alert rule-set 제거", description = "PrometheusRule/anycloud-<id> 를 DELETE_RESOURCE.")
    public ResponseEntity<ApiSuccessResponse<AlertRuleApplyResult>> uninstallRuleSet(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @PathVariable @NotBlank @Size(max = 64) String ruleSetId,
            @RequestParam(required = false) String namespace) {
        AlertRuleApplyResult result = alertRuleInstaller.uninstall(clusterName, ruleSetId, namespace, null);
        return ok("alert rule-set deleted", result);
    }

    // ---- Grafana dashboard ----

    @GetMapping("/clusters/{clusterName}/observability/dashboard")
    @Operation(summary = "Grafana 접근 URL", description = "Ingress > LoadBalancer 순. 외부 노출이 없으면 GRAFANA_NOT_EXPOSED 에러.")
    public ResponseEntity<ApiSuccessResponse<DashboardLocation>> dashboard(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String serviceName) {
        DashboardLocation result = dashboardLocator.locate(clusterName, namespace, serviceName);
        return ok("dashboard location", result);
    }

    // ---- Standard query catalog (편집 baseline 으로 노출) ----

    @GetMapping("/observability/standard-queries")
    @Operation(
            summary = "starter 가 사용하는 표준 PromQL 카탈로그",
            description = "각 항목의 promql 에 {{window}} placeholder 가 있으면 hasWindow=true. "
                    + "Frontend 는 이 목록을 preset 으로 표시하고 사용자 편집 가능.")
    public ResponseEntity<ApiSuccessResponse<List<StandardQuery>>> standardQueries() {
        return ok("standard queries", StandardQueries.catalog());
    }

    // ---- High-level typed metrics (starter 의 ClusterMetricsService 위임) ----

    @GetMapping("/clusters/{clusterName}/metrics/standard/node-cpu")
    @Operation(summary = "Node 별 CPU 사용률 (idle 제외)", description = "vector by node, window default 5m.")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> nodeCpu(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false, defaultValue = "5m") String window) {
        return ok("node CPU", metricsService.nodeCpuUsage(clusterName, parseWindow(window), null));
    }

    @GetMapping("/clusters/{clusterName}/metrics/standard/node-memory")
    @Operation(summary = "Node 별 메모리 사용 bytes", description = "used = MemTotal - MemAvailable.")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> nodeMemory(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        return ok("node memory", metricsService.nodeMemoryUsage(clusterName, null));
    }

    @GetMapping("/clusters/{clusterName}/metrics/standard/namespace-cpu")
    @Operation(summary = "Namespace 별 CPU 사용 cores", description = "vector by namespace.")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> namespaceCpu(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false, defaultValue = "5m") String window) {
        return ok("namespace CPU", metricsService.namespaceCpuUsage(clusterName, parseWindow(window), null));
    }

    @GetMapping("/clusters/{clusterName}/metrics/standard/namespace-memory")
    @Operation(summary = "Namespace 별 메모리 사용 bytes")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> namespaceMemory(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        return ok("namespace memory", metricsService.namespaceMemoryUsage(clusterName, null));
    }

    @GetMapping("/clusters/{clusterName}/metrics/standard/pod-phases")
    @Operation(summary = "Pod phase 분포", description = "Running / Pending / Failed / Succeeded / Unknown 별 count.")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> podPhases(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName) {
        return ok("pod phases", metricsService.podCountByPhase(clusterName, null));
    }

    @GetMapping("/clusters/{clusterName}/metrics/standard/top-cpu")
    @Operation(summary = "TopK 노드 CPU", description = "k default 5, window default 5m.")
    public ResponseEntity<ApiSuccessResponse<List<MetricSample>>> topCpu(
            @PathVariable @NotBlank @Pattern(regexp = CLUSTER_REGEXP) @Size(max = CLUSTER_MAX) String clusterName,
            @RequestParam(required = false, defaultValue = "5") int k,
            @RequestParam(required = false, defaultValue = "5m") String window) {
        return ok("top-K CPU", metricsService.topKNodesByCpu(clusterName, k, parseWindow(window), null));
    }

    /** "5m" / "30s" / "1h" 같은 PromQL window → Duration. 형식 오류 시 default 5m. */
    private static Duration parseWindow(String s) {
        if (s == null || s.isBlank()) return Duration.ofMinutes(5);
        try {
            char unit = s.charAt(s.length() - 1);
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (unit) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                default -> Duration.ofMinutes(5);
            };
        } catch (Exception e) {
            return Duration.ofMinutes(5);
        }
    }

    // controller-local @ExceptionHandler(ObservabilityException) 는
    // GlobalExceptionHandler 로 이동.

    private static <T> ResponseEntity<ApiSuccessResponse<T>> ok(String msg, T data) {
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), msg, data));
    }
}
