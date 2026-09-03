package com.aipaas.anycloud.domain.provisioning.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterStateMachineProperties;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AdminStateMachineController 회귀 보호 — graph 조회 + strict toggle.
 */
class AdminStateMachineControllerTest extends AbstractUnitTest {

    private MockMvc mvc;
    private VmClusterStateMachineProperties props;

    @BeforeEach
    void setUp() {
        props = new VmClusterStateMachineProperties();
        mvc = MockMvcBuilders.standaloneSetup(new AdminStateMachineController(props))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void getGraph_returnsAllStatesWithTransitionsAndMermaid() throws Exception {
        // Phase strict mode default = true.
        mvc.perform(get("/v1/admin/state-machine/vmcluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.states").isArray())
                .andExpect(jsonPath("$.data.states[0].name").exists())
                .andExpect(jsonPath("$.data.states[0].transitions").isArray())
                .andExpect(jsonPath("$.data.mermaid").value(org.hamcrest.Matchers.startsWith("stateDiagram-v2")))
                .andExpect(jsonPath("$.data.strict").value(true));
    }

    @Test
    void getStrict_returnsStrictByDefault() throws Exception {
        // strict 활성이 default. 위반 시 즉시 throw.
        mvc.perform(get("/v1/admin/state-machine/vmcluster/strict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strict").value(true))
                .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.containsString("Strict")));
    }

    @Test
    void postStrict_togglesPropertyInMemory() throws Exception {
        // default true → false toggle 후 다시 true.
        assertThat(props.isStrict()).isTrue();

        mvc.perform(post("/v1/admin/state-machine/vmcluster/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strict\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous").value(true))
                .andExpect(jsonPath("$.data.current").value(false));

        assertThat(props.isStrict()).isFalse();

        // toggle back
        mvc.perform(post("/v1/admin/state-machine/vmcluster/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strict\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous").value(false))
                .andExpect(jsonPath("$.data.current").value(true));

        assertThat(props.isStrict()).isTrue();
    }
}
