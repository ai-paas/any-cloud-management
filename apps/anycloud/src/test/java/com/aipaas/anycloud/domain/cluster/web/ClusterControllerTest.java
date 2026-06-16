package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller;
import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.cluster.model.BootstrapInfo;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryQueryService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * v1 cluster controller 의 RESTful 회귀 방지.
 * <ul>
 *   <li>POST /v1/clusters → 202 Accepted + Location: /v1/operations/...</li>
 *   <li>PATCH /v1/clusters/{n} spec → 202</li>
 *   <li>POST /v1/clusters/{n}/operations type → 200/202</li>
 *   <li>응답 body 에 OperationResponse + links</li>
 * </ul>
 */
class ClusterControllerTest extends AbstractUnitTest {

    @Mock
    ClusterFacade clusterFacade;

    @Mock
    OperationService operationService;

    @Mock
    ClusterCapabilitiesSink clusterCapabilitiesSink;

    @Mock
    VmClusterStateHistoryQueryService stateHistoryQueryService;

    @Mock
    AgentApiManagedInstaller agentApiManagedInstaller;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(agentApiManagedInstaller.prepareBootstrap(anyString()))
                .thenReturn(new BootstrapInfo(null, null, null, null, null, null));
        mvc = MockMvcBuilders.standaloneSetup(new ClusterController(
                        clusterFacade,
                        operationService,
                        clusterCapabilitiesSink,
                        stateHistoryQueryService,
                        agentApiManagedInstaller))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    private OperationEntity stubOperationEntity(
            String id, OperationType type, String resourceId, OperationState state) {
        return OperationEntity.builder()
                .id(id)
                .type(type)
                .resourceType("cluster")
                .resourceId(resourceId)
                .state(state)
                .percent(0)
                .build();
    }

    /** Step 2 Phase B — controller 가 *Domain 호출하므로 domain record stub. */
    private com.aipaas.anycloud.domain.operation.Operation stubOperationDomain(
            String id, OperationType type, String resourceId, OperationState state) {
        return org.mapstruct.factory.Mappers.getMapper(
                        com.aipaas.anycloud.domain.operation.mapper.OperationMapper.class)
                .toDomain(stubOperationEntity(id, type, resourceId, state));
    }

    @Test
    void createVmCluster_returns202_withLocationAndOperationBody() throws Exception {
        when(clusterFacade.createDomain(any()))
                .thenReturn(stubOperationDomain(
                        "op-create01", OperationType.CREATE_CLUSTER, "demo-aws-01", OperationState.RUNNING));

        String body = json.writeValueAsString(java.util.Map.of(
                "source", "vm",
                "clusterName", "demo-aws-01",
                "spec", java.util.Map.of("provider", "aws", "region", "ap-northeast-2", "environment", "dev")));

        mvc.perform(post("/v1/clusters").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-create01"))
                // : data 가 ClusterRegistrationResponse wrapper — operation 필드 안에 OperationResponse.
                .andExpect(jsonPath("$.data.operation.id").value("op-create01"))
                .andExpect(jsonPath("$.data.operation.type").value("CREATE_CLUSTER"))
                .andExpect(jsonPath("$.data.operation.state").value("RUNNING"))
                .andExpect(jsonPath("$.links.self").value("/v1/operations/op-create01"))
                .andExpect(jsonPath("$.links.resource").value("/v1/clusters/demo-aws-01"))
                .andExpect(jsonPath("$.links.events").value("/v1/operations/op-create01/events"));
    }

    @Test
    void createRegistered_returns201() throws Exception {
        when(clusterFacade.createDomain(any()))
                .thenReturn(stubOperationDomain(
                        "op-reg01", OperationType.CREATE_CLUSTER, "imported-01", OperationState.SUCCEEDED));

        String body = json.writeValueAsString(java.util.Map.of(
                "source", "registered",
                "clusterName", "imported-01",
                "spec", java.util.Map.of("provider", "aws", "clusterType", "EKS")));

        mvc.perform(post("/v1/clusters").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/operations/op-reg01"));
    }

    @Test
    void patchScale_returns202() throws Exception {
        when(clusterFacade.patchDomain(anyString(), any()))
                .thenReturn(stubOperationDomain(
                        "op-scale01", OperationType.SCALE_CLUSTER, "demo-aws-01", OperationState.RUNNING));

        String body = json.writeValueAsString(java.util.Map.of("spec", java.util.Map.of("workerCount", 5)));

        mvc.perform(patch("/v1/clusters/demo-aws-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-scale01"))
                .andExpect(jsonPath("$.data.type").value("SCALE_CLUSTER"));
    }

    @Test
    void patchScale_rejectsWorkerCountOutOfRange() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("spec", java.util.Map.of("workerCount", 99)));
        mvc.perform(patch("/v1/clusters/demo-aws-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_rejectsEmptySpec() throws Exception {
        when(clusterFacade.patchDomain(anyString(), any()))
                .thenThrow(new IllegalArgumentException("spec must include exactly one of..."));
        String body = json.writeValueAsString(java.util.Map.of("spec", java.util.Map.of()));
        mvc.perform(patch("/v1/clusters/demo-aws-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOperation_retryWorkflow_returns202() throws Exception {
        when(clusterFacade.createOperationDomain(anyString(), anyString()))
                .thenReturn(stubOperationDomain(
                        "op-retry01", OperationType.RETRY_WORKFLOW, "demo-aws-01", OperationState.RUNNING));

        String body = json.writeValueAsString(java.util.Map.of("type", "retryWorkflow"));
        mvc.perform(post("/v1/clusters/demo-aws-01/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("RETRY_WORKFLOW"));
    }

    @Test
    void createOperation_refreshStatus_returns202_evenWhenAlreadySucceeded() throws Exception {
        // UX #8: 항상 202 통일. Sync op (refreshStatus) 도 응답 body 의 state=SUCCEEDED 로 식별.
        // 클라이언트는 state 만 보면 polling 필요 여부 판단 — HTTP code 분기 불필요.
        when(clusterFacade.createOperationDomain(anyString(), anyString()))
                .thenReturn(stubOperationDomain(
                        "op-refresh01", OperationType.REFRESH_STATUS, "demo-aws-01", OperationState.SUCCEEDED));

        String body = json.writeValueAsString(java.util.Map.of("type", "refreshStatus"));
        mvc.perform(post("/v1/clusters/demo-aws-01/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-refresh01"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
    }

    @Test
    void delete_returns202_withLocation() throws Exception {
        when(clusterFacade.deleteDomain(anyString()))
                .thenReturn(stubOperationDomain(
                        "op-del01", OperationType.DELETE_CLUSTER, "demo-aws-01", OperationState.RUNNING));

        mvc.perform(delete("/v1/clusters/demo-aws-01"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/operations/op-del01"));
    }

    @Test
    void getOne_returns200_withHateoasLinks() throws Exception {
        when(clusterFacade.getOne(anyString()))
                .thenReturn(com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse.builder()
                        .source("vm")
                        .clusterName("demo-aws-01")
                        .status("READY")
                        .build());
        mvc.perform(get("/v1/clusters/demo-aws-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clusterName").value("demo-aws-01"))
                .andExpect(jsonPath("$.links.self").value("/v1/clusters/demo-aws-01"))
                .andExpect(jsonPath("$.links.operations").value("/v1/clusters/demo-aws-01/operations"))
                .andExpect(jsonPath("$.links.helmReleases").value("/v1/clusters/demo-aws-01/helm-releases"))
                .andExpect(jsonPath("$.links.events").value("/v1/clusters/demo-aws-01/events"));
    }

    @Test
    void connectivityCheck_returns201_withResultResource() throws Exception {
        when(clusterFacade.checkConnectivity(anyString())).thenReturn(true);
        mvc.perform(post("/v1/clusters/demo-aws-01/connectivity-checks"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.clusterName").value("demo-aws-01"))
                .andExpect(jsonPath("$.data.checkedAt").exists());
    }

    // ===== capability sync 변경 =====

    @Test
    void patchCapabilities_setHasGpuNodesTrue_returns200_andInvokesSink() throws Exception {
        mvc.perform(patch("/v1/clusters/demo-aws-01/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hasGpuNodes\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clusterName").value("demo-aws-01"))
                .andExpect(jsonPath("$.data.hasGpuNodes").value(true));
        org.mockito.Mockito.verify(clusterCapabilitiesSink).setHasGpuNodes("demo-aws-01", true);
    }

    @Test
    void patchCapabilities_setHasGpuNodesFalse_returns200() throws Exception {
        mvc.perform(patch("/v1/clusters/demo-aws-01/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hasGpuNodes\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasGpuNodes").value(false));
        org.mockito.Mockito.verify(clusterCapabilitiesSink).setHasGpuNodes("demo-aws-01", false);
    }

    @Test
    void patchCapabilities_emptyBody_returns400() throws Exception {
        mvc.perform(patch("/v1/clusters/demo-aws-01/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // sink 가 호출되지 않음.
        org.mockito.Mockito.verify(clusterCapabilitiesSink, org.mockito.Mockito.never())
                .setHasGpuNodes(anyString(), org.mockito.Mockito.anyBoolean());
    }
}
