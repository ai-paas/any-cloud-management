package com.aipaas.anycloud.domain.agent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AdminAgentController 회귀 보호 — heartbeat staleness threshold 조회/변경 endpoint.
 */
class AdminAgentControllerTest extends AbstractUnitTest {

    @Mock
    AgentHealthService agentHealthService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAgentController(agentHealthService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void getThreshold_returnsCurrentDuration() throws Exception {
        when(agentHealthService.getHeartbeatStalenessThreshold()).thenReturn(Duration.ofSeconds(90));

        mvc.perform(get("/v1/admin/agent/heartbeat-staleness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threshold").value("PT1M30S"))
                .andExpect(jsonPath("$.data.thresholdSeconds").value(90));
    }

    @Test
    void postThreshold_acceptsIsoFormatAndUpdates() throws Exception {
        when(agentHealthService.getHeartbeatStalenessThreshold())
                .thenReturn(Duration.ofSeconds(90))
                .thenReturn(Duration.ofMinutes(2)); // after set call returns new value (not actually used here)

        mvc.perform(post("/v1/admin/agent/heartbeat-staleness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\": \"PT2M\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous").value("PT1M30S"))
                .andExpect(jsonPath("$.data.current").value("PT2M"))
                .andExpect(jsonPath("$.data.currentSeconds").value(120));

        verify(agentHealthService, times(1)).setHeartbeatStalenessThreshold(Duration.ofMinutes(2));
    }

    @Test
    void postThreshold_acceptsSecondsFallback() throws Exception {
        when(agentHealthService.getHeartbeatStalenessThreshold()).thenReturn(Duration.ofSeconds(90));

        mvc.perform(post("/v1/admin/agent/heartbeat-staleness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdSeconds\": 180}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentSeconds").value(180));

        verify(agentHealthService).setHeartbeatStalenessThreshold(Duration.ofSeconds(180));
    }

    @Test
    void postThreshold_rejectsEmptyBody() throws Exception {
        // 두 필드 모두 비어있음 → IllegalArgumentException → GlobalExceptionHandler 가 4xx.
        // getThreshold 가 호출되지 않으므로 stub 불필요.
        mvc.perform(post("/v1/admin/agent/heartbeat-staleness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());

        verify(agentHealthService, times(0)).setHeartbeatStalenessThreshold(any());
    }

    @Test
    void postThreshold_rejectsInvalidIsoString() throws Exception {
        mvc.perform(post("/v1/admin/agent/heartbeat-staleness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\": \"90s\"}")) // not ISO-8601
                .andExpect(status().is4xxClientError());
    }
}
