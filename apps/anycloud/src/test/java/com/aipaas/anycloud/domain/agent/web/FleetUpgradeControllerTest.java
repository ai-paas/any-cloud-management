package com.aipaas.anycloud.domain.agent.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService.UpgradeResult;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService.ClusterEntry;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService.FleetPreview;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FleetUpgradeControllerTest extends AbstractUnitTest {

    @Mock
    FleetUpgradeService service;

    @Mock
    AgentUpgradeService upgradeService;

    @Mock
    com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeOrchestrator orchestrator;
    // controller 가 이제 FleetUpgradeRunQueryService 위임. test 는 real service +
    // repo mock (가벼운 단일 호출이라 mock 보다 production-fidelity 가 더 안전).
    @Mock
    com.aipaas.anycloud.domain.agent.FleetUpgradeRunRepository runRepository;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var runQueryService = new com.aipaas.anycloud.domain.agent.upgrade.impl.FleetUpgradeRunQueryServiceImpl(
                runRepository,
                org.mapstruct.factory.Mappers.getMapper(
                        com.aipaas.anycloud.domain.agent.upgrade.mapper.FleetUpgradeRunMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(
                        new FleetUpgradeController(service, upgradeService, orchestrator, runQueryService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void preview_returnsWaveDistribution() throws Exception {
        Map<ClusterAgentUpgradeWave, Long> waveCounts = new LinkedHashMap<>();
        waveCounts.put(ClusterAgentUpgradeWave.CANARY, 1L);
        waveCounts.put(ClusterAgentUpgradeWave.GENERAL, 2L);
        Map<String, Long> versionCounts = Map.of("v1.0.0", 2L, "v0.9.0", 1L);
        Map<ClusterAgentUpgradeWave, List<ClusterEntry>> byWave = new LinkedHashMap<>();
        byWave.put(
                ClusterAgentUpgradeWave.CANARY,
                List.of(new ClusterEntry("canary-1", ClusterAgentUpgradeWave.CANARY, List.of("v1.0.0"))));
        byWave.put(
                ClusterAgentUpgradeWave.GENERAL,
                List.of(
                        new ClusterEntry("prod-1", ClusterAgentUpgradeWave.GENERAL, List.of("v1.0.0")),
                        new ClusterEntry("prod-2", ClusterAgentUpgradeWave.GENERAL, List.of("v0.9.0"))));
        when(service.preview()).thenReturn(new FleetPreview(3, waveCounts, versionCounts, byWave));

        mvc.perform(get("/v1/fleet/upgrade/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalClusters").value(3))
                .andExpect(jsonPath("$.data.waveCounts.CANARY").value(1))
                .andExpect(jsonPath("$.data.waveCounts.GENERAL").value(2))
                .andExpect(jsonPath("$.data.byWave.CANARY[0].clusterName").value("canary-1"));
    }

    @Test
    void setWave_validRequest_returnsOk() throws Exception {
        String body = json.writeValueAsString(Map.of("wave", "CANARY"));
        mvc.perform(patch("/v1/clusters/demo-aws-01/upgrade-wave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(service).setWave(eq("demo-aws-01"), eq(ClusterAgentUpgradeWave.CANARY));
    }

    @Test
    void setWave_invalidWave_returns400() throws Exception {
        String body = json.writeValueAsString(Map.of("wave", "TOTALLY_INVALID"));
        mvc.perform(patch("/v1/clusters/demo-aws-01/upgrade-wave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upgrade_validImage_returnsResult() throws Exception {
        when(upgradeService.upgradeCluster(eq("prod-1"), eq("aipaas/cluster-agent:v1.2.3")))
                .thenReturn(
                        new UpgradeResult("prod-1", "aipaas/cluster-agent:v1.2.3", "IN_PROGRESS", "Manifest applied."));
        String body = json.writeValueAsString(Map.of("targetImage", "aipaas/cluster-agent:v1.2.3"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/prod-1/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.targetImage").value("aipaas/cluster-agent:v1.2.3"));
    }

    @Test
    void upgrade_invalidImage_returns400() throws Exception {
        String body = json.writeValueAsString(Map.of("targetImage", "bad image with spaces"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/prod-1/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upgrade_emptyImage_returns400() throws Exception {
        String body = json.writeValueAsString(Map.of("targetImage", ""));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/prod-1/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitFleetUpgrade_validRequest_returns202() throws Exception {
        var run = com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity.builder()
                .runId("00000000-0000-0000-0000-000000000001")
                .targetImage("aipaas/cluster-agent:v1.2.3")
                .wavesCsv("CANARY,GENERAL")
                .status(com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunStatus.PLANNED)
                .build();
        org.mockito.Mockito.when(orchestrator.submit(
                        org.mockito.ArgumentMatchers.eq("aipaas/cluster-agent:v1.2.3"),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(run);
        String body = json.writeValueAsString(
                Map.of("targetImage", "aipaas/cluster-agent:v1.2.3", "waves", List.of("CANARY", "GENERAL")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/fleet/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PLANNED"));
    }

    @Test
    void submitFleetUpgrade_pausedWaveInRequest_rejected() throws Exception {
        // PAUSED 가 waves 에 들어가면 orchestrator 가 INVALID_INPUT_VALUE 거부 — 컨트롤러는 그 예외 전파.
        org.mockito.Mockito.when(orchestrator.submit(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.aipaas.anycloud.common.error.exception.CustomException(
                        "PAUSED wave cannot be a target",
                        com.aipaas.anycloud.common.error.enums.ErrorCode.INVALID_INPUT_VALUE));
        String body = json.writeValueAsString(
                Map.of("targetImage", "aipaas/cluster-agent:v1.2.3", "waves", List.of("PAUSED")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/fleet/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
