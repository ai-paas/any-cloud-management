package com.aipaas.anycloud.domain.provisioning.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.provisioning.api.response.VmNodeListItemResponse;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** VM 화면은 클러스터가 아니라 노드를 한 행씩 본다. */
class VmNodeControllerTest extends AbstractUnitTest {

    @Mock
    VmClusterQueryService vmClusterQueryService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new VmNodeController(vmClusterQueryService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    private VmNodeListItemResponse node(String name, String role) {
        return new VmNodeListItemResponse(
                name, role, "i-1", "10.0.0.1", "1.2.3.4", "1.2.3.4", "demo", "OCI", "ap-tokyo-1", "dev", "READY");
    }

    @Test
    void listsEveryNodeAcrossClusters() throws Exception {
        when(vmClusterQueryService.listNodes(isNull(), isNull()))
                .thenReturn(List.of(node("demo-master-0", "master"), node("demo-worker-0", "worker")));

        mvc.perform(get("/v1/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].nodeName").value("demo-master-0"))
                .andExpect(jsonPath("$.data.items[0].role").value("master"))
                .andExpect(jsonPath("$.data.items[0].clusterName").value("demo"))
                .andExpect(jsonPath("$.data.items[0].infraStatus").value("READY"));
    }

    @Test
    void narrowsToOneClusterWhenAsked() throws Exception {
        when(vmClusterQueryService.listNodes(isNull(), eq("demo")))
                .thenReturn(List.of(node("demo-master-0", "master")));

        mvc.perform(get("/v1/nodes").param("clusterName", "demo")).andExpect(status().isOk());

        verify(vmClusterQueryService).listNodes(null, "demo");
    }

    @Test
    void narrowsByProvider() throws Exception {
        when(vmClusterQueryService.listNodes(eq("OCI"), isNull())).thenReturn(List.of());

        mvc.perform(get("/v1/nodes").param("provider", "OCI")).andExpect(status().isOk());

        verify(vmClusterQueryService).listNodes("OCI", null);
    }
}
