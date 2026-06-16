package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClusterValidationControllerTest extends AbstractUnitTest {

    @Mock
    VmClusterService vmClusterService;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClusterValidationController(vmClusterService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void validate_returns201_andLink_to_createCluster() throws Exception {
        when(vmClusterService.preflightVmCluster(any()))
                .thenReturn(VmClusterPreflightResponse.builder()
                        .readyToProvision(true)
                        .clusterName("demo-aws-01")
                        .build());
        String body = json.writeValueAsString(java.util.Map.of(
                "clusterName", "demo-aws-01",
                "clusterProvider", "AWS",
                "environment", "dev",
                "region", "ap-northeast-2"));

        mvc.perform(post("/v1/cluster-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.links.createCluster").value("/v1/clusters"));
    }

    @Test
    void validate_rejectsInvalidProvider_atBeanValidation() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "clusterName", "demo-aws-01",
                "clusterProvider", "BAD PROVIDER", // contains space — pattern violation
                "environment", "dev",
                "region", "ap-northeast-2"));
        mvc.perform(post("/v1/cluster-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
