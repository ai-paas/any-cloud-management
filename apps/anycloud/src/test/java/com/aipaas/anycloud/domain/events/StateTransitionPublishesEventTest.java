package com.aipaas.anycloud.domain.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRecorder;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 상태 변경 시 화면 통보 필요.
 *
 * <p>전이는 이 지점을 단일 경유. 개별 호출부마다 발행을 추가하면 누락 발생.
 */
class StateTransitionPublishesEventTest extends AbstractUnitTest {

    @Mock
    VmClusterStateHistoryRepository repository;

    @Mock
    ApplicationEventPublisher publisher;

    @InjectMocks
    VmClusterStateHistoryRecorder recorder;

    private VmClusterEntity cluster() {
        VmClusterEntity e = new VmClusterEntity();
        e.setId("vmc-1");
        e.setClusterName("demo");
        return e;
    }

    @Test
    void announcesTheClusterThatChanged() {
        recorder.record(cluster(), VmClusterStatus.PROVISIONING, VmClusterStatus.READY, "done");

        ArgumentCaptor<ResourceChangedEvent> captured = ArgumentCaptor.forClass(ResourceChangedEvent.class);
        verify(publisher).publishEvent(captured.capture());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().type()).isEqualTo("vmCluster");
        org.assertj.core.api.Assertions.assertThat(captured.getValue().name()).isEqualTo("demo");
    }

    @Test
    void announcesEvenWhenTheHistoryWriteFails() {
        // 이력 저장은 best-effort. 저장 실패로 화면이 이전 상태로 남으면 안 됨.
        org.mockito.Mockito.when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        recorder.record(cluster(), VmClusterStatus.PROVISIONING, VmClusterStatus.FAILED, "boom");

        verify(publisher).publishEvent(any(ResourceChangedEvent.class));
    }
}
