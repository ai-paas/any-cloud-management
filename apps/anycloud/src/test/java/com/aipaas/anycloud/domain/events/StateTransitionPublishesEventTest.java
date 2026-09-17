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
 * 상태가 바뀌면 화면이 알아야 한다.
 *
 * <p>전이는 여기 한 곳을 지난다. 개별 호출부마다 발행을 넣으면 언젠가 빠뜨린다.
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
        // 이력 저장은 best-effort 다. 그것 때문에 화면이 옛 상태로 남으면 안 된다.
        org.mockito.Mockito.when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        recorder.record(cluster(), VmClusterStatus.PROVISIONING, VmClusterStatus.FAILED, "boom");

        verify(publisher).publishEvent(any(ResourceChangedEvent.class));
    }
}
