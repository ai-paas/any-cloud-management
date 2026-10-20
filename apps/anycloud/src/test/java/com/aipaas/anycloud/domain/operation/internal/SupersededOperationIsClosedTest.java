package com.aipaas.anycloud.domain.operation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationRepository;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/**
 * 한 자원에 새 작업이 시작되면 이전 작업은 종료 상태.
 *
 * <p>생성 중 삭제 시 해당 CREATE 는 진행 불가 상태인데도 RUNNING 으로 잔존해, 클러스터가 READY
 * 인 상황에서 화면은 "BOOTSTRAP_NODES_READY 64%" 표시. 시간 기반 정리(2시간)로는 해당 구간
 * 미처리. 새 작업 생성 시점에 이전 작업의 결과는 이미 확정.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SupersededOperationIsClosedTest extends AbstractUnitTest {

    @Mock
    OperationRepository repository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    OperationServiceImpl service;

    private OperationEntity active(String id, OperationState state) {
        return OperationEntity.builder()
                .id(id)
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("demo")
                .state(state)
                .currentStep("BOOTSTRAP_NODES_READY")
                .percent(64)
                .build();
    }

    private void stubSave() {
        when(repository.save(any(OperationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubExisting(OperationEntity... rows) {
        when(repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                        anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(rows));
        for (OperationEntity row : rows) {
            when(repository.findById(row.getId())).thenReturn(java.util.Optional.of(row));
        }
    }

    @Test
    void aNewOperationClosesTheOneItReplaces() {
        OperationEntity stuck = active("op-old", OperationState.RUNNING);
        stubExisting(stuck);
        stubSave();

        service.start(OperationType.DELETE_CLUSTER, "cluster", "demo", null, 1);

        assertThat(stuck.getState()).isEqualTo(OperationState.CANCELLED);
        assertThat(stuck.getEndedAt()).isNotNull();
    }

    @Test
    void theClosedOperationSaysWhyItStopped() {
        // CANCELLED 만 기록하면 사용자 취소와 구분 불가.
        OperationEntity stuck = active("op-old", OperationState.PENDING);
        stubExisting(stuck);
        stubSave();

        service.start(OperationType.DELETE_CLUSTER, "cluster", "demo", null, 1);

        assertThat(stuck.getErrorMessage()).contains("DELETE_CLUSTER");
    }

    @Test
    void finishedOperationsAreLeftAlone() {
        OperationEntity done = active("op-done", OperationState.SUCCEEDED);
        stubExisting(done);
        stubSave();

        service.start(OperationType.CREATE_CLUSTER, "cluster", "demo", null, 3);

        assertThat(done.getState()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(done.getErrorMessage()).isNull();
    }

    @Test
    void theNewOperationStartsCleanlyEvenSoTheOldOneCannotBeClosed() {
        // 정리 실패로 새 작업이 시작되지 못하면 자원 미생성. 기록보다 작업이 우선.
        when(repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                        anyString(), anyString(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));
        stubSave();

        OperationEntity created = service.start(OperationType.CREATE_CLUSTER, "cluster", "demo", null, 3);

        assertThat(created.getState()).isEqualTo(OperationState.PENDING);
    }
}
