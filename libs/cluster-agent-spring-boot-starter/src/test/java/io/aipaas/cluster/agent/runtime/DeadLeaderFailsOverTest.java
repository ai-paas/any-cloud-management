package io.aipaas.cluster.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.AgentSession;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 응답하지 않는 leader 는 leader 가 아니다.
 *
 * <p>agent 를 롤링 재시작하면 죽은 pod 의 stream 이 backend 에 그대로 남는다. leader 는 가장 오래된
 * stream 이라 새 pod 가 붙어도 명령은 계속 죽은 쪽으로 가고, 클러스터의 모든 기능이 20초 timeout 만
 * 반복한다. 실제로 그래서 재설치 직후 클러스터 전체가 먹통이 됐다.
 *
 * <p>TCP 가 끊겼다는 것을 알려면 keepalive 를 기다려야 하는데, 그 사이를 메우는 것은 응답 자체다.
 */
class DeadLeaderFailsOverTest {

    private static class RecordingObserver implements StreamObserver<ControlMessage> {
        final List<ControlMessage> sent = new ArrayList<>();
        volatile Throwable closedWith;

        @Override
        public synchronized void onNext(ControlMessage value) {
            sent.add(value);
        }

        @Override
        public void onError(Throwable t) {
            closedWith = t;
        }

        @Override
        public void onCompleted() {}
    }

    /** 정리는 timeout 체인에서 일어난다 — 호출자의 future 가 끝난 시점과 같지 않다. */
    private void awaitSessions(AgentSessionRegistry r, int expected) throws Exception {
        for (int i = 0; i < 100 && r.findAll("demo").size() != expected; i++) {
            Thread.sleep(20);
        }
    }

    private CommandResponse ok() {
        return CommandResponse.newBuilder().setStatus(Status.OK).build();
    }

    @Test
    void aLeaderThatNeverAnswersIsDropped() throws Exception {
        AgentSessionRegistry r = new AgentSessionRegistry();
        AgentSession dead = r.register("demo", "old", new RecordingObserver());
        AgentSession live = r.register("demo", "new", new RecordingObserver());

        r.sendCommand("demo", ControlMessage.newBuilder(), 1)
                .handle((x, e) -> null)
                .get(5, TimeUnit.SECONDS);
        awaitSessions(r, 1);

        assertThat(r.findAll("demo")).doesNotContain(dead).contains(live);
        assertThat(r.find("demo")).contains(live);
    }

    @Test
    void theLastSessionIsDroppedToo() throws Exception {
        // 죽은 것 하나뿐이면 남겨둘 이유가 없다. 없는 편이 즉시 실패해 재연결을 부른다.
        AgentSessionRegistry r = new AgentSessionRegistry();
        r.register("demo", "only", new RecordingObserver());

        r.sendCommand("demo", ControlMessage.newBuilder(), 1)
                .handle((x, e) -> null)
                .get(5, TimeUnit.SECONDS);
        awaitSessions(r, 0);

        assertThat(r.findAll("demo")).isEmpty();
    }

    @Test
    void anEvictedStreamIsToldSoItCanReconnect() throws Exception {
        // 레지스트리에서만 빼면 agent 는 아직 붙어 있다고 믿는다. 끊은 줄 모르니 다시 연결하지도
        // 않고, 그 클러스터는 pod 가 재시작될 때까지 영영 닿지 않는다 — 실제로 그렇게 죽었다.
        AgentSessionRegistry r = new AgentSessionRegistry();
        RecordingObserver observer = new RecordingObserver();
        r.register("demo", "only", observer);

        r.sendCommand("demo", ControlMessage.newBuilder(), 1)
                .handle((x, e) -> null)
                .get(5, TimeUnit.SECONDS);
        awaitSessions(r, 0);

        assertThat(observer.closedWith).isNotNull();
    }

    @Test
    void aStreamAnsweringDuringTheWaitSurvivesATimeout() throws Exception {
        // 명령이 몰리면 응답이 시한을 넘겨 도착한다. 그 늦은 응답도 stream 이 살아 있다는 증거다 —
        // 기다리는 동안 무언가 답했다면 끊지 않는다. 바쁠 때마다 끊기면 부하가 걸린 순간에 죽는다.
        AgentSessionRegistry r = new AgentSessionRegistry();
        RecordingObserver observer = new RecordingObserver();
        AgentSession session = r.register("demo", "busy", observer);

        var first = r.sendCommand("demo", ControlMessage.newBuilder(), 5);
        String firstId = observer.sent.get(0).getRequestId();
        var second = r.sendCommand("demo", ControlMessage.newBuilder(), 1);

        // 두 번째 명령을 기다리는 사이에 첫 번째 응답이 도착한다.
        r.completeResponse(firstId, ok());
        first.get(5, TimeUnit.SECONDS);
        second.handle((x, e) -> null).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        assertThat(r.findAll("demo")).contains(session);
    }
}
