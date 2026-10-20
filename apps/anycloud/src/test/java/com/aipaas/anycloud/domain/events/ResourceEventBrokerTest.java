package com.aipaas.anycloud.domain.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 새로고침 없이 화면에 최신 상태를 전달하는 채널.
 *
 * <p>데이터는 전송하지 않고 변경 발생 신호만 전달. 데이터 제공은 REST 담당. 스트림으로 데이터를
 * 전달하면 두 경로의 결과가 달라짐.
 */
class ResourceEventBrokerTest extends AbstractUnitTest {

    /** 전송 내역을 기록하는 emitter. 네트워크 없이 팬아웃 검증. */
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
        // nginx proxy_read_timeout 900s. 유휴 스트림은 프록시가 종료.
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
