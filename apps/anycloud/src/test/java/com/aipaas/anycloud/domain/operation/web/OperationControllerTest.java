package com.aipaas.anycloud.domain.operation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperationControllerTest extends AbstractUnitTest {

    @Mock
    OperationService operationService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OperationController(operationService))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void search_returnsItemsList() throws Exception {
        OperationEntity e = OperationEntity.builder()
                .id("op-abc12345")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("demo-aws-01")
                .state(OperationState.RUNNING)
                .build();
        when(operationService.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of(e));

        mvc.perform(get("/v1/operations").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("op-abc12345"));
    }

    @Test
    void getOne_returnsOperationDto() throws Exception {
        OperationEntity e = OperationEntity.builder()
                .id("op-xyz98765")
                .type(OperationType.SCALE_CLUSTER)
                .resourceType("cluster")
                .resourceId("demo-aws-01")
                .state(OperationState.SUCCEEDED)
                .percent(100)
                .build();
        when(operationService.findById("op-xyz98765")).thenReturn(Optional.of(e));

        mvc.perform(get("/v1/operations/op-xyz98765"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("op-xyz98765"))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
    }

    @Test
    void getOne_notFound_returns400_viaIllegalArgument() throws Exception {
        when(operationService.findById("op-missing")).thenReturn(Optional.empty());
        mvc.perform(get("/v1/operations/op-missing")).andExpect(status().isBadRequest());
    }

    @Test
    void cancel_returns202_acceptedViaCustomMethod() throws Exception {
        OperationEntity e = OperationEntity.builder()
                .id("op-cancel01")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.CANCELLED)
                .build();
        when(operationService.cancel(anyString())).thenReturn(e);

        mvc.perform(post("/v1/operations/op-cancel01/cancel"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"));
    }
}
