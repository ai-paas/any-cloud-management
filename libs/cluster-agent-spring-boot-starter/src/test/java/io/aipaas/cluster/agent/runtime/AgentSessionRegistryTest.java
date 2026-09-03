package io.aipaas.cluster.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.AgentSession;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.SessionClosedException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * AgentSessionRegistry 의 동시성/lifecycle/명령 dispatch 회귀 보호.
 */
class AgentSessionRegistryTest  {

	/** Recording observer — backend → agent 로 보낸 ControlMessage 들을 수집. */
	private static class RecordingObserver implements StreamObserver<ControlMessage> {
		final List<ControlMessage> sent = new ArrayList<>();
		boolean completed = false;
		boolean errored = false;

		@Override
		public synchronized void onNext(ControlMessage value) {
			sent.add(value);
		}
		@Override public void onError(Throwable t) { errored = true; }
		@Override public void onCompleted() { completed = true; }
	}

	@Test
	void register_addsSessionToRegistry() {
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver obs = new RecordingObserver();

		AgentSession s = r.register("demo-aws-01", "instance-1", obs);

		assertThat(r.size()).isEqualTo(1);
		assertThat(r.find("demo-aws-01")).hasValueSatisfying(found ->
				assertThat(found.agentInstanceId()).isEqualTo("instance-1"));
		assertThat(s.clusterId()).isEqualTo("demo-aws-01");
	}

	@Test
	void register_differentInstances_coexistAsHA() {
		// 다른 instance 들은 강제 종료 없이 동시 active. Leader = 가장 오래된 (instance-1).
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver oldObs = new RecordingObserver();
		RecordingObserver newObs = new RecordingObserver();

		r.register("demo-aws-01", "instance-1", oldObs);
		r.register("demo-aws-01", "instance-2", newObs);

		// instance-1 의 stream 은 여전히 살아있어야 함 (HA).
		assertThat(oldObs.completed).isFalse();
		// find() 는 leader (oldest) 반환.
		assertThat(r.find("demo-aws-01")).hasValueSatisfying(s ->
				assertThat(s.agentInstanceId()).isEqualTo("instance-1"));
		// findAll 은 둘 다 포함.
		assertThat(r.findAll("demo-aws-01")).hasSize(2);
	}

	@Test
	void register_sameInstanceTwice_staleStreamRemoved() {
		// 같은 instance_id 가 재연결하면 stale 한 옛 stream 만 정리 (agent restart).
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver oldObs = new RecordingObserver();
		RecordingObserver newObs = new RecordingObserver();

		r.register("demo-aws-01", "instance-1", oldObs);
		r.register("demo-aws-01", "instance-1", newObs);

		// 옛 stream 은 onCompleted 됨.
		assertThat(oldObs.completed).isTrue();
		// 새 stream 만 남음.
		assertThat(r.findAll("demo-aws-01")).hasSize(1);
	}

	@Test
	void unregister_leaderRemoved_nextOldestPromoted() {
		// leader 끊기면 다음 oldest 가 자동 leader 승격.
		AgentSessionRegistry r = new AgentSessionRegistry();
		AgentSession leader = r.register("demo-aws-01", "instance-1", new RecordingObserver());
		r.register("demo-aws-01", "instance-2", new RecordingObserver());
		r.register("demo-aws-01", "instance-3", new RecordingObserver());

		r.unregister(leader);

		// instance-2 가 새 leader.
		assertThat(r.find("demo-aws-01")).hasValueSatisfying(s ->
				assertThat(s.agentInstanceId()).isEqualTo("instance-2"));
		assertThat(r.findAll("demo-aws-01")).hasSize(2);
	}

	@Test
	void unregister_followerRemoved_leaderUnchanged() {
		AgentSessionRegistry r = new AgentSessionRegistry();
		r.register("demo-aws-01", "instance-1", new RecordingObserver());
		AgentSession follower = r.register("demo-aws-01", "instance-2", new RecordingObserver());

		r.unregister(follower);

		assertThat(r.find("demo-aws-01")).hasValueSatisfying(s ->
				assertThat(s.agentInstanceId()).isEqualTo("instance-1"));
		assertThat(r.findAll("demo-aws-01")).hasSize(1);
	}

	@Test
	void sendCommand_noSession_failsImmediately() {
		AgentSessionRegistry r = new AgentSessionRegistry();

		CompletableFuture<CommandResponse> f = r.sendCommand("ghost",
				ControlMessage.newBuilder(), 10);

		assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(NoActiveSessionException.class);
	}

	@Test
	void sendCommand_pushesToStreamWithGeneratedRequestId() throws Exception {
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver obs = new RecordingObserver();
		r.register("demo-aws-01", "instance-1", obs);

		CompletableFuture<CommandResponse> future = r.sendCommand("demo-aws-01",
				ControlMessage.newBuilder().setCommand(
						io.aipaas.cluster.agent.v1.CommandRequest.newBuilder()
								.setType(CommandType.LIST_PODS).build()),
				10);

		// Stream 에 정확히 1개 ControlMessage 가 push 됐고 request_id 가 채워졌어야 함.
		assertThat(obs.sent).hasSize(1);
		String requestId = obs.sent.get(0).getRequestId();
		assertThat(requestId).isNotBlank();
		assertThat(obs.sent.get(0).getCommand().getType()).isEqualTo(CommandType.LIST_PODS);

		// Future 는 아직 미완료.
		assertThat(future.isDone()).isFalse();

		// 응답 수신 시 future 완료.
		CommandResponse response = CommandResponse.newBuilder()
				.setStatus(Status.OK)
				.build();
		r.completeResponse(requestId, response);

		CommandResponse got = future.get(1, TimeUnit.SECONDS);
		assertThat(got.getStatus()).isEqualTo(Status.OK);
	}

	@Test
	void completeResponse_unknownRequestId_dropped() {
		AgentSessionRegistry r = new AgentSessionRegistry();
		// 아무 stream 도 없어도 silently drop — 로그만.
		r.completeResponse("ghost-request", CommandResponse.getDefaultInstance());
	}

	@Test
	void sendCommand_timesOutIfNoResponse() {
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver obs = new RecordingObserver();
		r.register("demo-aws-01", "instance-1", obs);

		// 1 초 timeout.
		CompletableFuture<CommandResponse> future = r.sendCommand("demo-aws-01",
				ControlMessage.newBuilder().setCommand(
						io.aipaas.cluster.agent.v1.CommandRequest.newBuilder().setType(CommandType.LIST_PODS).build()),
				1);

		assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(TimeoutException.class);
	}

	@Test
	void unregister_cancelsPendingCommandsForThatCluster() throws Exception {
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver obs = new RecordingObserver();
		AgentSession s = r.register("demo-aws-01", "instance-1", obs);

		CompletableFuture<CommandResponse> future = r.sendCommand("demo-aws-01",
				ControlMessage.newBuilder().setCommand(
						io.aipaas.cluster.agent.v1.CommandRequest.newBuilder().setType(CommandType.LIST_PODS).build()),
				30);

		// 응답 오기 전 stream 종료.
		r.unregister(s);

		assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(SessionClosedException.class);
	}

	@Test
	void concurrentSendCommand_safeForManyThreads() throws Exception {
		// 동시에 100개 명령 dispatch — onNext 가 thread-safe 하다는 가정 검증.
		AgentSessionRegistry r = new AgentSessionRegistry();
		RecordingObserver obs = new RecordingObserver();
		r.register("demo-aws-01", "instance-1", obs);

		int n = 100;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(n);
		List<CompletableFuture<CommandResponse>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			new Thread(() -> {
				try { start.await(); } catch (InterruptedException ignored) {}
				CompletableFuture<CommandResponse> f = r.sendCommand("demo-aws-01",
						ControlMessage.newBuilder().setCommand(
								io.aipaas.cluster.agent.v1.CommandRequest.newBuilder().setType(CommandType.LIST_PODS).build()),
						30);
				synchronized (futures) { futures.add(f); }
				done.countDown();
			}).start();
		}
		start.countDown();
		done.await(5, TimeUnit.SECONDS);

		// 100개 모두 push 됐고 request_id 가 서로 다름.
		assertThat(obs.sent).hasSize(n);
		long distinctIds = obs.sent.stream().map(ControlMessage::getRequestId).distinct().count();
		assertThat(distinctIds).isEqualTo(n);
	}

}
