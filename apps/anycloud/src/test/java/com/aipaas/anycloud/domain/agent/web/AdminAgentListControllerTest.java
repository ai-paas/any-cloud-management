package com.aipaas.anycloud.domain.agent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.agent.api.response.AdminAgentListResponse;
import com.aipaas.anycloud.domain.agent.internal.AdminAgentQueryService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AdminAgentListController 회귀 보호 — GET /v1/admin/agents fleet list.
 */
class AdminAgentListControllerTest extends AbstractUnitTest {

    @Mock
    AdminAgentQueryService queryService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAgentListController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void list_default_returnsEnvelope() throws Exception {
        when(queryService.query(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminAgentListResponse(
                        List.of(new AdminAgentListResponse.Item(
                                "agt-1",
                                "aws-prod-01",
                                "pod-1",
                                "ACTIVE",
                                "0.3.0",
                                LocalDateTime.now(),
                                12L,
                                null)),
                        1,
                        0,
                        50,
                        1));

        mvc.perform(get("/v1/admin/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].agentId").value("agt-1"))
                .andExpect(jsonPath("$.data.items[0].clusterName").value("aws-prod-01"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    void list_withFilters_parsesCsv() throws Exception {
        when(queryService.query(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminAgentListResponse(List.of(), 0, 0, 20, 0));

        mvc.perform(get("/v1/admin/agents")
                        .param("status", "DEGRADED,FAILED")
                        .param("clusterName", "aws-prod-01,gcp-stage-1")
                        .param("versionPrefix", "0.3")
                        .param("lastSeenOlderThanSec", "3600")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void list_unknownStatus_silentSkip() throws Exception {
        when(queryService.query(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminAgentListResponse(List.of(), 0, 0, 50, 0));

        // ACTIVE 만 valid, BOGUS 는 skip — controller 가 400 던지지 않고 그대로 진행
        mvc.perform(get("/v1/admin/agents").param("status", "ACTIVE,BOGUS"))
                .andExpect(status().isOk());
    }
}
