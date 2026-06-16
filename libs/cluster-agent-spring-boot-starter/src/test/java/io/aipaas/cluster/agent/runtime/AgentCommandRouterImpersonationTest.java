package io.aipaas.cluster.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.identity.ImpersonationContext;
import io.aipaas.cluster.agent.identity.ImpersonationIdentity;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.ImpersonateExtra;
import io.aipaas.cluster.agent.v1.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AgentCommandRouter} 의 impersonation field 주입 회귀.
 *
 * <p>모든 send() 호출이 ImpersonationContext.current() 를 읽어 CommandRequest 의
 * impersonate_user / impersonate_groups / impersonate_extras 에 매핑하는지 격리 검증.
 * holder 비었을 때 / context provider 자체 null 인 legacy 생성자 경로도 확인.
 */
class AgentCommandRouterImpersonationTest {

	private AgentSessionRegistry sessionRegistry;

	@BeforeEach
	void setUp() {
		sessionRegistry = Mockito.mock(AgentSessionRegistry.class,
				Mockito.withSettings().strictness(Strictness.LENIENT));
		when(sessionRegistry.sendCommand(any(), any(ControlMessage.Builder.class), anyInt()))
				.thenReturn(CompletableFuture.completedFuture(
						CommandResponse.newBuilder().setStatus(Status.OK).build()));
	}

	@SuppressWarnings("unchecked")
	private ObjectProvider<ImpersonationContext> providerOf(ImpersonationContext ctx) {
		ObjectProvider<ImpersonationContext> mock = Mockito.mock(ObjectProvider.class);
		when(mock.getIfAvailable()).thenReturn(ctx);
		return mock;
	}

	private ImpersonationContext ctxOf(ImpersonationIdentity identity) {
		return () -> Optional.ofNullable(identity);
	}

	private ControlMessage captureSend(String cluster) {
		ArgumentCaptor<ControlMessage.Builder> captor =
				ArgumentCaptor.forClass(ControlMessage.Builder.class);
		Mockito.verify(sessionRegistry).sendCommand(eq(cluster), captor.capture(), anyInt());
		return captor.getValue().build();
	}

	@Test
	void send_withIdentity_injectsImpersonateUserAndGroups() {
		var identity = ImpersonationIdentity.of("alice", List.of("dev-team", "viewers"));
		var router = new AgentCommandRouter(sessionRegistry, providerOf(ctxOf(identity)));

		router.listResources("c1", "default", "Pod", 50, "", "").join();

		ControlMessage msg = captureSend("c1");
		assertThat(msg.getCommand().getImpersonateUser()).isEqualTo("alice");
		assertThat(msg.getCommand().getImpersonateGroupsList())
				.containsExactly("dev-team", "viewers");
	}

	@Test
	void send_withExtras_injectsImpersonateExtras() {
		Map<String, List<String>> extras = Map.of(
				"scopes", List.of("openid", "profile"),
				"clusters", List.of("c1"));
		var identity = new ImpersonationIdentity("alice", List.of(), extras);
		var router = new AgentCommandRouter(sessionRegistry, providerOf(ctxOf(identity)));

		router.applyManifest("c1", "default", "apiVersion: v1\nkind: Pod", false).join();

		ControlMessage msg = captureSend("c1");
		Map<String, ImpersonateExtra> sent = msg.getCommand().getImpersonateExtrasMap();
		assertThat(sent).containsKeys("scopes", "clusters");
		assertThat(sent.get("scopes").getValuesList()).containsExactly("openid", "profile");
	}

	@Test
	void send_emptyIdentity_leavesImpersonateFieldsBlank() {
		// holder 비어 있음 → 현재 admin-equivalent 동작 보존.
		var router = new AgentCommandRouter(sessionRegistry,
				providerOf(ImpersonationContext.empty()));

		router.listResources("c1", "default", "Pod", 50, "", "").join();

		ControlMessage msg = captureSend("c1");
		assertThat(msg.getCommand().getImpersonateUser()).isEmpty();
		assertThat(msg.getCommand().getImpersonateGroupsList()).isEmpty();
		assertThat(msg.getCommand().getImpersonateExtrasMap()).isEmpty();
	}

	@Test
	void send_nullContextProvider_legacyConstructor_noInjection() {
		// 구식 생성자 (test convenience) — provider 자체가 없어도 NPE 없이 동작.
		var router = new AgentCommandRouter(sessionRegistry);

		router.listResources("c1", "default", "Pod", 50, "", "").join();

		ControlMessage msg = captureSend("c1");
		assertThat(msg.getCommand().getImpersonateUser()).isEmpty();
	}

	@Test
	void send_providerReturnsNull_noInjection() {
		// ObjectProvider 가 bean 미등록인 경우 getIfAvailable() → null.
		@SuppressWarnings("unchecked")
		ObjectProvider<ImpersonationContext> nullProvider = Mockito.mock(ObjectProvider.class);
		when(nullProvider.getIfAvailable()).thenReturn(null);
		var router = new AgentCommandRouter(sessionRegistry, nullProvider);

		router.listResources("c1", "default", "Pod", 50, "", "").join();

		ControlMessage msg = captureSend("c1");
		assertThat(msg.getCommand().getImpersonateUser()).isEmpty();
	}

	@Test
	void send_passesCommandTypeAndParamsThrough() {
		var router = new AgentCommandRouter(sessionRegistry);

		router.send("c1", CommandType.GET_AGENT_CONFIG, Struct.getDefaultInstance(), 15).join();

		ControlMessage msg = captureSend("c1");
		assertThat(msg.getCommand().getType()).isEqualTo(CommandType.GET_AGENT_CONFIG);
		assertThat(msg.getCommand().getTimeoutSeconds()).isEqualTo(15);
	}
}
