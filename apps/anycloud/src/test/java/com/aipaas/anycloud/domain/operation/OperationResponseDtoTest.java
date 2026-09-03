package com.aipaas.anycloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

class OperationResponseTest extends AbstractUnitTest {

    @Test
    void from_mapsAllFields() {
        OperationEntity e = OperationEntity.builder()
                .id("op-abc12345")
                .type(OperationType.SCALE_CLUSTER)
                .resourceType("cluster")
                .resourceId("demo-aws-01")
                .state(OperationState.RUNNING)
                .currentStep("BOOTSTRAP")
                .stepIndex(2)
                .totalSteps(3)
                .percent(66)
                .errorMessage(null)
                .build();
        var dto = OperationResponse.from(e);
        assertThat(dto.id()).isEqualTo("op-abc12345");
        assertThat(dto.type()).isEqualTo("SCALE_CLUSTER");
        assertThat(dto.resourceId()).isEqualTo("demo-aws-01");
        assertThat(dto.state()).isEqualTo("RUNNING");
        assertThat(dto.progress()).isNotNull();
        assertThat(dto.progress().currentStep()).isEqualTo("BOOTSTRAP");
        assertThat(dto.progress().percent()).isEqualTo(66);
    }

    @Test
    void from_noProgressFields_omitsProgressBlock() {
        OperationEntity e = OperationEntity.builder()
                .id("op-noprog")
                .type(OperationType.REFRESH_STATUS)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.SUCCEEDED)
                .build();
        var dto = OperationResponse.from(e);
        assertThat(dto.progress()).isNull();
    }
}
