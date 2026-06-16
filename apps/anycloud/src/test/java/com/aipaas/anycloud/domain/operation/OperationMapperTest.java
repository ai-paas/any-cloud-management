package com.aipaas.anycloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.operation.mapper.OperationMapper;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Operation ↔ OperationEntity 변환 회귀 보호. MapStruct instance ({@link Mappers#getMapper})
 * round-trip 검증.
 */
class OperationMapperTest {

    private final OperationMapper mapper = Mappers.getMapper(OperationMapper.class);

    @Test
    void toDomain_preservesAllFields() {
        LocalDateTime now = LocalDateTime.now();
        OperationEntity entity = OperationEntity.builder()
                .id("op-1")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("cl-1")
                .state(OperationState.RUNNING)
                .currentStep("provision")
                .stepIndex(2)
                .totalSteps(5)
                .percent(40)
                .requestPayload("{\"a\":1}")
                .resultPayload(null)
                .errorMessage(null)
                .requestId("req-1")
                .principal("alice")
                .startedAt(now.minusMinutes(5))
                .endedAt(null)
                .createdAt(now.minusMinutes(6))
                .updatedAt(now)
                .build();

        Operation domain = mapper.toDomain(entity);

        assertThat(domain.id()).isEqualTo("op-1");
        assertThat(domain.type()).isEqualTo(OperationType.CREATE_CLUSTER);
        assertThat(domain.state()).isEqualTo(OperationState.RUNNING);
        assertThat(domain.currentStep()).isEqualTo("provision");
        assertThat(domain.totalSteps()).isEqualTo(5);
        assertThat(domain.requestPayload()).isEqualTo("{\"a\":1}");
        assertThat(domain.requestId()).isEqualTo("req-1");
        assertThat(domain.startedAt()).isEqualTo(now.minusMinutes(5));
        assertThat(domain.endedAt()).isNull();
        assertThat(domain.isActive()).isTrue();
        assertThat(domain.isTerminal()).isFalse();
    }

    @Test
    void toEntity_roundTrip_preservesFields() {
        Operation original =
                Operation.pending("op-2", OperationType.DELETE_CLUSTER, "cluster", "cl-2", "{\"force\":true}", 3);

        OperationEntity entity = mapper.toEntity(original);
        Operation round = mapper.toDomain(entity);

        assertThat(round.id()).isEqualTo(original.id());
        assertThat(round.type()).isEqualTo(original.type());
        assertThat(round.resourceType()).isEqualTo(original.resourceType());
        assertThat(round.resourceId()).isEqualTo(original.resourceId());
        assertThat(round.state()).isEqualTo(OperationState.PENDING);
        assertThat(round.totalSteps()).isEqualTo(3);
        assertThat(round.percent()).isEqualTo(0);
        assertThat(round.requestPayload()).isEqualTo("{\"force\":true}");
    }

    @Test
    void isTerminal_recognizesAllTerminalStates() {
        assertThat(stateOnly(OperationState.SUCCEEDED).isTerminal()).isTrue();
        assertThat(stateOnly(OperationState.FAILED).isTerminal()).isTrue();
        assertThat(stateOnly(OperationState.CANCELLED).isTerminal()).isTrue();
        assertThat(stateOnly(OperationState.RUNNING).isTerminal()).isFalse();
        assertThat(stateOnly(OperationState.PENDING).isTerminal()).isFalse();
    }

    @Test
    void mapper_handlesNull() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toEntity(null)).isNull();
    }

    private static Operation stateOnly(OperationState s) {
        return new Operation(
                "x",
                OperationType.CREATE_CLUSTER,
                "cluster",
                "x",
                s,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
