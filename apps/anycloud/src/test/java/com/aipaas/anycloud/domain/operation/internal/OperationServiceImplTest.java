package com.aipaas.anycloud.domain.operation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationRepository;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Operation lifecycle 상태 머신 + 영속 흐름 회귀 방지.
 * Repository 는 mock, save() 는 입력 그대로 반환하도록 stubbing.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationServiceImplTest extends AbstractUnitTest {

    @Mock
    OperationRepository repository;

    @InjectMocks
    OperationServiceImpl service;

    private void stubSavePassthrough() {
        when(repository.save(any(OperationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void start_createsOperationInPendingState() {
        stubSavePassthrough();
        OperationEntity op =
                service.start(OperationType.SCALE_CLUSTER, "cluster", "demo-aws-01", "{\"workerCount\":5}", 3);

        assertThat(op.getId()).startsWith("op-").hasSize(15); // op- + 12 chars
        assertThat(op.getType()).isEqualTo(OperationType.SCALE_CLUSTER);
        assertThat(op.getResourceType()).isEqualTo("cluster");
        assertThat(op.getResourceId()).isEqualTo("demo-aws-01");
        assertThat(op.getState()).isEqualTo(OperationState.PENDING);
        assertThat(op.getTotalSteps()).isEqualTo(3);
        assertThat(op.getPercent()).isEqualTo(0);
        assertThat(op.getRequestPayload()).isEqualTo("{\"workerCount\":5}");
    }

    @Test
    void start_totalStepsZero_storesNull() {
        stubSavePassthrough();
        OperationEntity op = service.start(OperationType.REFRESH_STATUS, "cluster", "x", null, 0);
        assertThat(op.getTotalSteps()).isNull();
    }

    @Test
    void markRunning_transitionsPendingToRunning_andSetsStartedAt() {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.DELETE_CLUSTER, "cluster", "x", null, 1);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity result = service.markRunning(initial.getId());
        assertThat(result.getState()).isEqualTo(OperationState.RUNNING);
        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    void markRunning_isIdempotent_doesNotOverwriteStartedAt() throws InterruptedException {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.SCALE_CLUSTER, "cluster", "x", null, 1);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity first = service.markRunning(initial.getId());
        java.time.LocalDateTime firstStartedAt = first.getStartedAt();
        Thread.sleep(5);

        OperationEntity second = service.markRunning(initial.getId());
        // 두 번째 호출은 PENDING 이 아니므로 no-op — startedAt 그대로.
        assertThat(second.getStartedAt()).isEqualTo(firstStartedAt);
        assertThat(second.getState()).isEqualTo(OperationState.RUNNING);
    }

    @Test
    void markRunning_onTerminalOp_isNoOp() {
        OperationEntity completed = OperationEntity.builder()
                .id("op-done")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.SUCCEEDED)
                .build();
        when(repository.findById("op-done")).thenReturn(java.util.Optional.of(completed));

        OperationEntity result = service.markRunning("op-done");
        assertThat(result.getState()).isEqualTo(OperationState.SUCCEEDED);
    }

    @Test
    void updateProgress_clampsPercentTo0_100() {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.SCALE_CLUSTER, "cluster", "x", null, 3);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity over = service.updateProgress(initial.getId(), "BOOTSTRAP", 2, 150);
        assertThat(over.getPercent()).isEqualTo(100);

        OperationEntity under = service.updateProgress(initial.getId(), "BOOTSTRAP", 2, -5);
        assertThat(under.getPercent()).isEqualTo(0);

        OperationEntity ok = service.updateProgress(initial.getId(), "BOOTSTRAP", 2, 66);
        assertThat(ok.getPercent()).isEqualTo(66);
    }

    @Test
    void complete_setsSucceededPercent100AndEndedAt() {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.CREATE_CLUSTER, "cluster", "x", null, 3);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity result = service.complete(initial.getId(), "{\"ok\":true}");
        assertThat(result.getState()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(result.getPercent()).isEqualTo(100);
        assertThat(result.getEndedAt()).isNotNull();
        assertThat(result.getResultPayload()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void fail_setsFailedAndErrorMessage() {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.CREATE_CLUSTER, "cluster", "x", null, 3);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity result = service.fail(initial.getId(), "provider quota exceeded");
        assertThat(result.getState()).isEqualTo(OperationState.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("provider quota exceeded");
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void cancel_setsCancelledAndEndedAt() {
        stubSavePassthrough();
        OperationEntity initial = service.start(OperationType.CREATE_CLUSTER, "cluster", "x", null, 3);
        when(repository.findById(initial.getId())).thenReturn(java.util.Optional.of(initial));

        OperationEntity result = service.cancel(initial.getId());
        assertThat(result.getState()).isEqualTo(OperationState.CANCELLED);
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void mutate_unknownId_throwsIllegalArgument() {
        when(repository.findById("op-missing")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.markRunning("op-missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("op-missing");
    }

    @Test
    void findLatestActiveByResource_skipsTerminalEntries() {
        OperationEntity terminal = OperationEntity.builder()
                .id("op-old")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.SUCCEEDED)
                .build();
        OperationEntity active = OperationEntity.builder()
                .id("op-active")
                .type(OperationType.SCALE_CLUSTER)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.RUNNING)
                .build();
        when(repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                        org.mockito.ArgumentMatchers.eq("cluster"),
                        org.mockito.ArgumentMatchers.eq("x"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(active, terminal));

        var found = service.findLatestActiveByResource("cluster", "x");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("op-active");
    }

    @Test
    void findLatestActiveByResource_emptyWhenAllTerminal() {
        OperationEntity terminal = OperationEntity.builder()
                .id("op-done")
                .type(OperationType.CREATE_CLUSTER)
                .resourceType("cluster")
                .resourceId("x")
                .state(OperationState.SUCCEEDED)
                .build();
        when(repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                        org.mockito.ArgumentMatchers.eq("cluster"),
                        org.mockito.ArgumentMatchers.eq("x"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(terminal));

        assertThat(service.findLatestActiveByResource("cluster", "x")).isEmpty();
    }
}
