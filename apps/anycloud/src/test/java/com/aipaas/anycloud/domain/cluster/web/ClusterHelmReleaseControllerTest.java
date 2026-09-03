package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.chart.ChartService;
import com.aipaas.anycloud.domain.chart.api.ChartHistoryItem;
import com.aipaas.anycloud.domain.chart.api.response.ChartHistoryResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReleasesResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartStatusResponse;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClusterHelmReleaseControllerTest extends AbstractUnitTest {

    @Mock
    ChartService chartService;

    @Mock
    OperationService operationService;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClusterHelmReleaseController(chartService, operationService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    private OperationEntity stubOp(String id, OperationType type, String releaseName, OperationState state) {
        return OperationEntity.builder()
                .id(id)
                .type(type)
                .resourceType("helmRelease")
                .resourceId(releaseName)
                .state(state)
                .build();
    }

    @Test
    void list_returnsReleasesPayload() throws Exception {
        ChartReleasesResponse payload = ChartReleasesResponse.builder()
                .releases(java.util.List.of(ChartReleasesResponse.ReleaseInfo.builder()
                        .name("ingress")
                        .namespace("ingress-nginx")
                        .status("deployed")
                        .build()))
                .build();
        when(chartService.getReleases(anyString(), any())).thenReturn(payload);

        mvc.perform(get("/v1/clusters/demo-aws-01/helm-releases").param("namespace", "ingress-nginx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releases[0].name").value("ingress"));
    }

    @Test
    void install_returns202_withLocationAndLinks() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(
                        stubOp("op-install01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(
                        stubOp("op-install01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.RUNNING));

        String body = json.writeValueAsString(java.util.Map.of(
                "releaseName", "ingress",
                "chart", "bitnami/nginx",
                "version", "15.3.0",
                "namespace", "ingress-nginx",
                "valuesYaml", "replicaCount: 3\n"));

        mvc.perform(post("/v1/clusters/demo-aws-01/helm-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-install01"))
                .andExpect(jsonPath("$.data.id").value("op-install01"))
                .andExpect(jsonPath("$.data.type").value("INSTALL_HELM_RELEASE"))
                .andExpect(jsonPath("$.links.self").value("/v1/operations/op-install01"))
                .andExpect(jsonPath("$.links.resource").value("/v1/clusters/demo-aws-01/helm-releases/ingress"));
    }

    @Test
    void install_acceptsValuesObject_convertsToYaml() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp("op-vobj01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp("op-vobj01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.RUNNING));

        String body = json.writeValueAsString(java.util.Map.of(
                "releaseName", "ingress",
                "chart", "bitnami/nginx",
                "namespace", "ingress-nginx",
                "values",
                        java.util.Map.of(
                                "replicaCount", 3, "image", java.util.Map.of("repository", "nginx", "tag", "1.25"))));
        mvc.perform(post("/v1/clusters/demo-aws-01/helm-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value("op-vobj01"));
    }

    @Test
    void install_rejectsBothValuesAndValuesYaml() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "releaseName", "ingress",
                "chart", "bitnami/nginx",
                "values", java.util.Map.of("replicaCount", 3),
                "valuesYaml", "replicaCount: 5\n"));
        mvc.perform(post("/v1/clusters/demo-aws-01/helm-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void install_multipart_acceptsValuesFile() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp("op-mp01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp("op-mp01", OperationType.INSTALL_HELM_RELEASE, "ingress", OperationState.RUNNING));

        org.springframework.mock.web.MockMultipartFile valuesFile = new org.springframework.mock.web.MockMultipartFile(
                "valuesFile", "values.yaml", "application/x-yaml", "replicaCount: 5\n".getBytes());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                "/v1/clusters/demo-aws-01/helm-releases")
                        .file(valuesFile)
                        .param("releaseName", "ingress")
                        .param("chart", "bitnami/nginx")
                        .param("namespace", "ingress-nginx"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("INSTALL_HELM_RELEASE"));
    }

    @Test
    void install_rejectsInvalidChartFormat() throws Exception {
        // chart 가 "repo/chart" 형식 안 맞으면 @Pattern 위반.
        String body = json.writeValueAsString(java.util.Map.of(
                "releaseName", "ingress",
                "chart", "no-slash-format",
                "namespace", "ingress-nginx"));
        mvc.perform(post("/v1/clusters/demo-aws-01/helm-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOne_returnsStatus() throws Exception {
        ChartStatusResponse payload = ChartStatusResponse.builder()
                .releaseName("ingress")
                .clusterName("demo-aws-01")
                .namespace("default")
                .status("DEPLOYED")
                .build();
        when(chartService.getChartStatus(anyString(), anyString(), any())).thenReturn(payload);

        mvc.perform(get("/v1/clusters/demo-aws-01/helm-releases/ingress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPLOYED"));
    }

    @Test
    void revisions_returnsHistory() throws Exception {
        ChartHistoryResponse payload = ChartHistoryResponse.builder()
                .clusterName("demo-aws-01")
                .releaseName("ingress")
                .namespace("default")
                .revisions(java.util.List.of(
                        ChartHistoryItem.builder()
                                .revision(1)
                                .status("superseded")
                                .build(),
                        ChartHistoryItem.builder()
                                .revision(2)
                                .status("deployed")
                                .build()))
                .build();
        when(chartService.getReleaseHistory(anyString(), anyString(), any(), anyInt()))
                .thenReturn(payload);

        mvc.perform(get("/v1/clusters/demo-aws-01/helm-releases/ingress/revisions")
                        .param("max", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisions[1].revision").value(2))
                .andExpect(jsonPath("$.data.revisions[1].status").value("deployed"));
    }

    @Test
    void rollback_returns200_withSucceededOperation() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp("op-rb01", OperationType.ROLLBACK_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp("op-rb01", OperationType.ROLLBACK_HELM_RELEASE, "ingress", OperationState.RUNNING));
        when(operationService.complete(anyString(), any()))
                .thenReturn(
                        stubOp("op-rb01", OperationType.ROLLBACK_HELM_RELEASE, "ingress", OperationState.SUCCEEDED));
        when(chartService.rollbackRelease(anyString(), anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(ChartStatusResponse.builder()
                        .releaseName("ingress")
                        .status("DEPLOYED")
                        .build());

        String body = json.writeValueAsString(java.util.Map.of("type", "rollback", "revision", 2, "wait", false));
        mvc.perform(post("/v1/clusters/demo-aws-01/helm-releases/ingress/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ROLLBACK_HELM_RELEASE"));
    }

    @Test
    void uninstall_default_returns202_andCallsServiceWithDefaults() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp(
                        "op-uninstall01", OperationType.UNINSTALL_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp(
                        "op-uninstall01", OperationType.UNINSTALL_HELM_RELEASE, "ingress", OperationState.RUNNING));
        when(chartService.uninstallRelease(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(ChartStatusResponse.builder()
                        .releaseName("ingress")
                        .namespace("default")
                        .status("deleted")
                        .build());

        mvc.perform(delete("/v1/clusters/demo-aws-01/helm-releases/ingress"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-uninstall01"))
                .andExpect(jsonPath("$.data.id").value("op-uninstall01"))
                .andExpect(jsonPath("$.data.type").value("UNINSTALL_HELM_RELEASE"))
                .andExpect(jsonPath("$.links.resource").value("/v1/clusters/demo-aws-01/helm-releases/ingress"));

        org.mockito.Mockito.verify(chartService)
                .uninstallRelease(
                        org.mockito.ArgumentMatchers.eq("demo-aws-01"),
                        org.mockito.ArgumentMatchers.eq("ingress"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(false),
                        org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void uninstall_withKeepHistoryAndWait_forwardsFlags() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp(
                        "op-uninstall02", OperationType.UNINSTALL_HELM_RELEASE, "ingress", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp(
                        "op-uninstall02", OperationType.UNINSTALL_HELM_RELEASE, "ingress", OperationState.RUNNING));
        when(chartService.uninstallRelease(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(ChartStatusResponse.builder()
                        .releaseName("ingress")
                        .namespace("web")
                        .status("uninstalled")
                        .build());

        mvc.perform(delete("/v1/clusters/demo-aws-01/helm-releases/ingress")
                        .param("namespace", "web")
                        .param("keepHistory", "true")
                        .param("wait", "true"))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(chartService)
                .uninstallRelease(
                        org.mockito.ArgumentMatchers.eq("demo-aws-01"),
                        org.mockito.ArgumentMatchers.eq("ingress"),
                        org.mockito.ArgumentMatchers.eq("web"),
                        org.mockito.ArgumentMatchers.eq(true),
                        org.mockito.ArgumentMatchers.eq(true));
        // markRunning + complete 정상 호출 (FAIL 경로 아님)
        org.mockito.Mockito.verify(operationService).markRunning("op-uninstall02");
        org.mockito.Mockito.verify(operationService)
                .complete(
                        org.mockito.ArgumentMatchers.eq("op-uninstall02"),
                        org.mockito.ArgumentMatchers.contains("\"status\":\"uninstalled\""));
    }

    @Test
    void uninstall_serviceThrows_marksOpFailed_andPropagates() throws Exception {
        when(operationService.start(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(stubOp(
                        "op-uninstall03", OperationType.UNINSTALL_HELM_RELEASE, "missing", OperationState.PENDING));
        when(operationService.markRunning(anyString()))
                .thenReturn(stubOp(
                        "op-uninstall03", OperationType.UNINSTALL_HELM_RELEASE, "missing", OperationState.RUNNING));
        when(chartService.uninstallRelease(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Error: uninstall: Release not loaded: missing"));

        mvc.perform(delete("/v1/clusters/demo-aws-01/helm-releases/missing")).andExpect(status().is5xxServerError());

        org.mockito.Mockito.verify(operationService)
                .fail(
                        org.mockito.ArgumentMatchers.eq("op-uninstall03"),
                        org.mockito.ArgumentMatchers.contains("Release not loaded"));
        org.mockito.Mockito.verify(operationService, org.mockito.Mockito.never())
                .complete(org.mockito.ArgumentMatchers.eq("op-uninstall03"), org.mockito.ArgumentMatchers.anyString());
    }
}
