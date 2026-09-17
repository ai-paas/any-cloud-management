package com.aipaas.anycloud.domain.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 화면이 새로고침 없이 최신을 보게 하는 채널.
 *
 * <p>데이터를 흘려보내지 않고 "무엇이 바뀌었다"는 신호만 보낸다. 데이터는 REST 가 계속 책임진다 —
 * 스트림으로 실어 나르면 두 경로가 서로 다른 답을 하게 된다.
 */
class ResourceEventBrokerTest extends AbstractUnitTest {

    /** 보낸 것을 기록하는 emitter. 실제 네트워크 없이 팬아웃을 확인한다. */
    private static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>();
        boolean fail;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (fail) throw new IOException("client gone");
            sent.add(builder);
        }
    }

    @Test
    void fansOutToEveryListener() {
        ResourceEventBroker broker = new ResourceEventBroker();
        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        broker.register(a);
        broker.register(b);

        broker.onResourceChanged(new ResourceChangedEvent("vmCluster", "demo"));

        assertThat(a.sent).hasSize(1);
        assertThat(b.sent).hasSize(1);
    }

    @Test
    void dropsListenersThatWentAway() {
        // 끊긴 연결을 남겨두면 이벤트마다 예외가 쌓이고 메모리도 샌다.
        ResourceEventBroker broker = new ResourceEventBroker();
        RecordingEmitter dead = new RecordingEmitter();
        dead.fail = true;
        RecordingEmitter alive = new RecordingEmitter();
        broker.register(dead);
        broker.register(alive);

        broker.onResourceChanged(new ResourceChangedEvent("vmCluster", "demo"));
        broker.onResourceChanged(new ResourceChangedEvent("vmCluster", "demo"));

        assertThat(broker.listenerCount()).isEqualTo(1);
        assertThat(alive.sent).hasSize(2);
    }

    @Test
    void unregisterStopsDelivery() {
        ResourceEventBroker broker = new ResourceEventBroker();
        RecordingEmitter a = new RecordingEmitter();
        broker.register(a);
        broker.unregister(a);

        broker.onResourceChanged(new ResourceChangedEvent("vmCluster", "demo"));

        assertThat(a.sent).isEmpty();
        assertThat(broker.listenerCount()).isZero();
    }

    @Test
    void heartbeatKeepsProxiesFromClosingIdleStreams() {
        // nginx proxy_read_timeout 이 900s 다. 조용한 스트림은 프록시가 끊는다.
        ResourceEventBroker broker = new ResourceEventBroker();
        RecordingEmitter a = new RecordingEmitter();
        broker.register(a);

        broker.heartbeat();

        assertThat(a.sent).hasSize(1);
    }

    @Test
    void survivesWhenNobodyIsListening() {
        ResourceEventBroker broker = new ResourceEventBroker();

        broker.onResourceChanged(new ResourceChangedEvent("vmCluster", "demo"));

        assertThat(broker.listenerCount()).isZero();
    }
}
