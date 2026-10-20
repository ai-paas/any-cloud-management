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
 * 무응답 leader 의 leader 자격 해제 검증.
 *
 * <p>agent 롤링 재시작 시 종료된 pod 의 stream 이 backend 에 잔존. leader 는 최고령 stream 이라
 * 새 pod 연결 후에도 명령이 종료된 쪽으로 전달되고, 클러스터 전체 기능이 20초 timeout 만 반복.
 * 재설치 직후 클러스터 전체 응답 불가로 이어짐.
 *
 * <p>TCP 종료 인지에는 keepalive 대기가 필요하며, 그 구간의 판정 근거는 응답 유무.
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

    /** 정리는 timeout 체인에서 일어난다. 호출자의 future 가 끝난 시점과 같지 않다. */
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
        // 않고, 그 클러스터는 pod 가 재시작될 때까지 영영 닿지 않는다. 실제로 그렇게 죽었다.
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
        // 명령 집중 시 응답이 시한을 초과해 도착. 지연 응답도 stream 유지의 근거.
        // 대기 구간에 응답이 있으면 연결 유지. 부하 시마다 끊으면 과부하 시점에 전체 실패.
        AgentSessionRegistry r = new AgentSessionRegistry();
        RecordingObserver observer = new RecordingObserver();
        AgentSession session = r.register("demo", "busy", observer);

        var first = r.sendCommand("demo", ControlMessage.newBuilder(), 5);
        String firstId = observer.sent.get(0).getRequestId();
        var second = r.sendCommand("demo", ControlMessage.newBuilder(), 1);

        // 두 번째 명령 대기 구간에 첫 번째 응답 도착.
        r.completeResponse(firstId, ok());
        first.get(5, TimeUnit.SECONDS);
        second.handle((x, e) -> null).get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        assertThat(r.findAll("demo")).contains(session);
    }
}
