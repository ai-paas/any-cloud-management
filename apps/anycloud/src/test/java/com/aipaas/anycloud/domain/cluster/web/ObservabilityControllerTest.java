package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.core.AlertsResult;
import io.aipaas.cluster.agent.observability.core.DashboardLocation;
import io.aipaas.cluster.agent.observability.core.MetricTargetsResult;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.observability.core.PromQLResult;
import io.aipaas.cluster.agent.observability.dashboard.DashboardLocator;
import io.aipaas.cluster.agent.observability.query.ObservabilityQueryService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ObservabilityController slice test — REST 매핑 / ExceptionHandler 의 HTTP status 매핑.
 *
 * <p>실제 starter 서비스는 mock — agent / Prometheus 호출 없음.
 */
class ObservabilityControllerTest extends AbstractUnitTest {

    @Mock
    ObservabilityQueryService queryService;

    @Mock
    DashboardLocator dashboardLocator;

    @Mock
    io.aipaas.cluster.agent.observability.metrics.ClusterMetricsService metricsService;

    @Mock
    io.aipaas.cluster.agent.observability.alerts.AlertRuleCatalog alertRuleCatalog;

    @Mock
    io.aipaas.cluster.agent.observability.alerts.AlertRuleInstaller alertRuleInstaller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // cluster_addon path 가 monitoring 설치 담당. ObservabilityException → HTTP status 매핑은
        // GlobalExceptionHandler 가 담당 — standalone test 도 advice 등록 필수.
        mvc = MockMvcBuilders.standaloneSetup(new ObservabilityController(
                        queryService,
                        dashboardLocator,
                        metricsService,
                        alertRuleCatalog,
                        alertRuleInstaller,
                        new ObjectMapper()))
                .setControllerAdvice(
                        new com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    // /observability/install 동기 endpoint 없음 — cluster_addon path 의 happy-path 는
    // AddonInstallerRegistryTest / RabbitMqAddonInstallListenerTest / AddonSpecResolverTest 가 cover.

    // ===== Query (instant + range + aggregate) =====

    @Test
    void query_happyPath_returnsRawPrometheusEnvelope() throws Exception {
        // raw passthrough — 응답이 Prometheus envelope 그대로.
        when(queryService.queryInstant(eq("c1"), eq("up"), eq(null), any(), any()))
                .thenReturn(new PromQLResult(
                        "c1",
                        "http://prom:9090",
                        false,
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"));

        mvc.perform(get("/v1/clusters/c1/metrics/query").param("promql", "up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.resultType").value("vector"));
    }

    @Test
    void query_acceptsNewQueryParamName() throws Exception {
        // 'query' 가 신규 표준 — 'promql' 과 동등.
        when(queryService.queryInstant(eq("c1"), eq("up"), eq(null), any(), any()))
                .thenReturn(new PromQLResult("c1", "http://prom:9090", false, "{\"status\":\"success\",\"data\":{}}"));

        mvc.perform(get("/v1/clusters/c1/metrics/query").param("query", "up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void query_missingBothQueryAndPromql_returns400() throws Exception {
        mvc.perform(get("/v1/clusters/c1/metrics/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MISSING_QUERY")));
    }

    @Test
    void query_noActiveAgent_returns503() throws Exception {
        when(queryService.queryInstant(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new ObservabilityException("NO_ACTIVE_AGENT", "no session"));

        mvc.perform(get("/v1/clusters/c1/metrics/query").param("promql", "up"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NO_ACTIVE_AGENT")));
    }

    @Test
    void query_timeout_returns504() throws Exception {
        when(queryService.queryInstant(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new ObservabilityException("TIMEOUT", "timed out"));

        mvc.perform(get("/v1/clusters/c1/metrics/query").param("promql", "up")).andExpect(status().isGatewayTimeout());
    }

    @Test
    void queryRange_returnsRawMatrixEnvelope() throws Exception {
        when(queryService.queryRange(eq("c1"), eq("up"), eq("100"), eq("200"), eq("10"), any(), any()))
                .thenReturn(new PromQLResult(
                        "c1",
                        "http://prom:9090",
                        true,
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}"));

        mvc.perform(get("/v1/clusters/c1/metrics/query_range")
                        .param("promql", "up")
                        .param("start", "100")
                        .param("end", "200")
                        .param("step", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.resultType").value("matrix"));
    }

    @Test
    void aggregate_returnsMapByCluster() throws Exception {
        Map<String, PromQLResult> result = new LinkedHashMap<>();
        result.put("c1", new PromQLResult("c1", "http://prom1:9090", false, "{\"data\":\"c1\"}"));
        result.put("c2", new PromQLResult("c2", "http://prom2:9090", false, "{\"data\":\"c2\"}"));
        when(queryService.queryAll(eq("up"), any(Duration.class))).thenReturn(result);

        mvc.perform(get("/v1/observability/aggregate").param("promql", "up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.c1.raw").value("{\"data\":\"c1\"}"))
                .andExpect(jsonPath("$.data.c2.raw").value("{\"data\":\"c2\"}"));
    }

    // ===== Targets / Alerts =====

    @Test
    void targets_returnsRawJson() throws Exception {
        when(queryService.listTargets(eq("c1"), eq("active"), any()))
                .thenReturn(new MetricTargetsResult("c1", "http://prom:9090", "{\"data\":{\"activeTargets\":[]}}"));

        mvc.perform(get("/v1/clusters/c1/observability/targets").param("state", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.raw").value(org.hamcrest.Matchers.containsString("activeTargets")));
    }

    @Test
    void alerts_returnsRawArray() throws Exception {
        when(queryService.listAlerts(eq("c1"), any())).thenReturn(new AlertsResult("c1", "http://am:9093", "[]"));

        mvc.perform(get("/v1/clusters/c1/observability/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.raw").value("[]"));
    }

    // ===== Dashboard =====

    @Test
    void dashboard_happyPath_returnsLocation() throws Exception {
        when(dashboardLocator.locate(eq("c1"), any(), any()))
                .thenReturn(new DashboardLocation(
                        "c1", "http://grafana.example.com", "grafana.example.com", 80, "Ingress"));

        mvc.perform(get("/v1/clusters/c1/observability/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://grafana.example.com"))
                .andExpect(jsonPath("$.data.exposure").value("Ingress"));
    }

    @Test
    void dashboard_notExposed_returns404() throws Exception {
        when(dashboardLocator.locate(anyString(), any(), any()))
                .thenThrow(new ObservabilityException("GRAFANA_NOT_EXPOSED", "no ingress"));

        mvc.perform(get("/v1/clusters/c1/observability/dashboard")).andExpect(status().isNotFound());
    }
}
