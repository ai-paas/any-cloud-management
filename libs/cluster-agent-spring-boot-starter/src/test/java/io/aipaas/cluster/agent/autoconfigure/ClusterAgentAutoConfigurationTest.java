package io.aipaas.cluster.agent.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.IdempotencyStore;
import io.aipaas.cluster.agent.identity.AgentIdentityAuthenticator;
import io.aipaas.cluster.agent.identity.ImpersonationContext;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.SigningKeyResolver;
import io.aipaas.cluster.agent.identity.ThreadLocalImpersonationContext;
import io.aipaas.cluster.agent.runtime.AgentCommandRouter;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.support.InMemoryAgentIdentityStore;
import io.aipaas.cluster.agent.support.InMemoryIdempotencyStore;
import io.aipaas.cluster.agent.terminal.ExecSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link ClusterAgentAutoConfiguration} wiring 회귀.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>core / runtime / identity bean 자동 등록 (default in-memory store).</li>
 *   <li>host 가 자체 SPI impl 등록 시 ConditionalOnMissingBean override.</li>
 *   <li>ImpersonationContext default 가 ThreadLocalImpersonationContext.</li>
 *   <li>WebSocket sub-config (PodExec) 는 본 비-web 컨텍스트에선 미생성 — gRPC bean 만 검증.</li>
 * </ul>
 */
class ClusterAgentAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					JacksonAutoConfiguration.class,
					ClusterAgentAutoConfiguration.class));

	@Test
	void defaultBeansRegistered_whenNoHostOverride() {
		runner.run(ctx -> {
			assertThat(ctx)
					.hasSingleBean(AgentSessionRegistry.class)
					.hasSingleBean(AgentCommandRouter.class)
					.hasSingleBean(KubeResourceService.class)
					.hasSingleBean(HelmReleaseService.class)
					.hasSingleBean(ExecSessionRegistry.class)
					.hasSingleBean(AgentIdentityAuthenticator.class)
					.hasSingleBean(SigningKeyResolver.class)
					.hasSingleBean(JwtRegistrationTokenService.class)
					.hasSingleBean(ImpersonationContext.class);
			// in-memory default stores.
			assertThat(ctx.getBean(AgentIdentityStore.class))
					.isInstanceOf(InMemoryAgentIdentityStore.class);
			assertThat(ctx.getBean(IdempotencyStore.class))
					.isInstanceOf(InMemoryIdempotencyStore.class);
			// default impersonation context = thread-local.
			assertThat(ctx.getBean(ImpersonationContext.class))
					.isInstanceOf(ThreadLocalImpersonationContext.class);
		});
	}

	@Test
	void hostOverridesAgentIdentityStore_replacesInMemoryDefault() {
		AgentIdentityStore custom = mock(AgentIdentityStore.class);
		runner.withBean(AgentIdentityStore.class, () -> custom).run(ctx -> {
			assertThat(ctx).hasSingleBean(AgentIdentityStore.class);
			assertThat(ctx.getBean(AgentIdentityStore.class)).isSameAs(custom);
		});
	}

	@Test
	void hostOverridesIdempotencyStore_replacesInMemoryDefault() {
		IdempotencyStore custom = mock(IdempotencyStore.class);
		runner.withBean(IdempotencyStore.class, () -> custom).run(ctx ->
				assertThat(ctx.getBean(IdempotencyStore.class)).isSameAs(custom));
	}

	@Test
	void hostOverridesImpersonationContext_replacesThreadLocalDefault() {
		// 비-Servlet (Reactive / async) host 가 자체 propagation 구현 등록 시 우선.
		ImpersonationContext custom = java.util.Optional::empty;
		runner.withBean(ImpersonationContext.class, () -> custom).run(ctx ->
				assertThat(ctx.getBean(ImpersonationContext.class)).isSameAs(custom));
	}

	@Test
	void runtimeServicesWiredWithObjectMapper() {
		// JacksonAutoConfiguration 이 ObjectMapper 자동 등록 — KubeResourceService / HelmReleaseService
		// 가 본 mapper 를 inject 받는지 (bean 생성 자체로 검증).
		runner.run(ctx -> {
			assertThat(ctx).hasSingleBean(ObjectMapper.class);
			assertThat(ctx).hasSingleBean(KubeResourceService.class);
			assertThat(ctx).hasSingleBean(HelmReleaseService.class);
		});
	}
}
