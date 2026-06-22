package io.aipaas.cluster.agent.autoconfigure;

import io.aipaas.cluster.agent.core.AgentIdentityStore;
import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import io.aipaas.cluster.agent.core.IdempotencyStore;
import io.aipaas.cluster.agent.support.InMemoryAgentIdentityStore;
import io.aipaas.cluster.agent.support.InMemoryIdempotencyStore;
import io.aipaas.cluster.agent.grpc.AgentRuntimeEndpoint;
import io.aipaas.cluster.agent.grpc.AuthMetadataInterceptor;
import io.aipaas.cluster.agent.identity.AgentIdentityAuthenticator;
import io.aipaas.cluster.agent.identity.AgentJwtProperties;
import io.aipaas.cluster.agent.identity.ImpersonationContext;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService;
import io.aipaas.cluster.agent.identity.PropertySigningKeyResolver;
import io.aipaas.cluster.agent.identity.SigningKeyResolver;
import io.aipaas.cluster.agent.identity.ThreadLocalImpersonationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentCommandRouter;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.logstream.LogStreamSessionRegistry;
import io.aipaas.cluster.agent.logstream.PodLogStreamService;
import io.aipaas.cluster.agent.terminal.ExecSessionRegistry;
import io.aipaas.cluster.agent.terminal.PodExecWebSocketHandler;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Cluster Agent starter auto-configuration — gRPC/runtime/terminal/identity bean wire.
 * <ul>
 *   <li>호스트 필수 bean: {@link AgentIdentityStore}, {@link IdempotencyStore}</li>
 *   <li>호스트 선택 bean: {@link AgentLifecycleListener} (multi-bean)</li>
 *   <li>활성 조건: classpath 에 {@link AgentRuntimeEndpoint}</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(AgentRuntimeEndpoint.class)
@EnableConfigurationProperties({ClusterAgentProperties.class, AgentJwtProperties.class})
public class ClusterAgentAutoConfiguration {

	// ---- core ----

	@Bean
	@ConditionalOnMissingBean
	public Clock clusterAgentClock() {
		return Clock.systemUTC();
	}

	/**
	 * Zero-config default. 호스트가 자체 DB-backed {@link AgentIdentityStore} 등록하면 자동 비활성.
	 * dev/PoC 즉시 시도 가능.
	 */
	@Bean
	@ConditionalOnMissingBean
	public AgentIdentityStore agentIdentityStore() {
		return new InMemoryAgentIdentityStore();
	}

	/**
	 * Zero-config default. JWT jti 의 1회 사용 강제 (in-memory). Production multi-instance 면
	 * DB-backed 권장 — 호스트 bean 등록 시 자동 우선.
	 */
	@Bean
	@ConditionalOnMissingBean
	public IdempotencyStore idempotencyStore() {
		return new InMemoryIdempotencyStore();
	}

	/**
	 * K8s Impersonation SPI default. Backend 가 자체 구현 등록하면 자동 override. interceptor 가
	 * set/clear 안 하는 환경 (test / async / toggle OFF) 에서 current() 는 항상 빈 Optional →
	 * starter 는 admin-equivalent 동작.
	 */
	@Bean
	@ConditionalOnMissingBean
	public ImpersonationContext impersonationContext() {
		return new ThreadLocalImpersonationContext();
	}

	// ---- runtime ----

	/**
	 * anycloud 단일 service 환경에선 영영 사용 안 할 가능성 큼. multi-instance 운영 시 gateway sticky
	 * session by cluster_id 권장 (docs/architecture/cluster-agent.md#backend-session-registry §0).
	 */
	@Bean
	@ConditionalOnMissingBean
	public AgentSessionRegistry agentSessionRegistry() {
		return new AgentSessionRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentCommandRouter agentCommandRouter(AgentSessionRegistry registry,
			org.springframework.beans.factory.ObjectProvider<ImpersonationContext> impersonationContextProvider) {
		return new AgentCommandRouter(registry, impersonationContextProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public KubeResourceService kubeResourceService(
			AgentSessionRegistry sessionRegistry,
			AgentCommandRouter commandRouter,
			ObjectMapper objectMapper,
			ClusterAgentProperties props) {
		return new KubeResourceService(
				sessionRegistry,
				commandRouter,
				objectMapper,
				props.routing().enabled(),
				props.routing().commandTimeoutSeconds());
	}

	@Bean
	@ConditionalOnMissingBean
	public HelmReleaseService helmReleaseService(
			AgentSessionRegistry sessionRegistry,
			AgentCommandRouter commandRouter,
			ObjectMapper objectMapper,
			ClusterAgentProperties props) {
		return new HelmReleaseService(
				sessionRegistry,
				commandRouter,
				objectMapper,
				props.routing().enabled(),
				props.routing().commandTimeoutSeconds());
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentHealthService agentHealthService(
			ClusterAgentProperties props,
			AgentIdentityStore identityStore,
			AgentSessionRegistry sessionRegistry,
			Clock clock) {
		return new AgentHealthService(
				props.health().heartbeatStalenessThreshold(),
				identityStore,
				sessionRegistry,
				clock);
	}

	// ---- identity ----

	@Bean
	@ConditionalOnMissingBean
	public AgentIdentityAuthenticator agentIdentityAuthenticator(
			AgentIdentityStore identityStore, Clock clock) {
		return new AgentIdentityAuthenticator(identityStore, clock);
	}

	/**
	 * Default — property 기반 (config 의 cluster-agent.jwt.secret). 호스트가 DB-backed 등
	 * 영구 저장 {@link SigningKeyResolver} bean 을 등록하면 자동으로 그쪽이 우선 (Conditional).
	 */
	@Bean
	@ConditionalOnMissingBean
	public SigningKeyResolver signingKeyResolver(AgentJwtProperties props) {
		return new PropertySigningKeyResolver(props);
	}

	@Bean
	@ConditionalOnMissingBean
	public JwtRegistrationTokenService jwtRegistrationTokenService(
			AgentJwtProperties props, IdempotencyStore idempotencyStore, Clock clock,
			SigningKeyResolver signingKeyResolver) {
		return new JwtRegistrationTokenService(props, idempotencyStore, clock, signingKeyResolver);
	}

	// ---- terminal ----

	@Bean
	@ConditionalOnMissingBean
	public ExecSessionRegistry execSessionRegistry() {
		return new ExecSessionRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public LogStreamSessionRegistry logStreamSessionRegistry() {
		return new LogStreamSessionRegistry();
	}

	@Bean
	@ConditionalOnMissingBean
	public PodLogStreamService podLogStreamService(
			LogStreamSessionRegistry sessionRegistry,
			AgentSessionRegistry agentSessionRegistry,
			ClusterAgentProperties props) {
		return new PodLogStreamService(sessionRegistry, agentSessionRegistry, props.routing().enabled());
	}

	@Bean
	@ConditionalOnMissingBean
	public PodExecWebSocketHandler podExecWebSocketHandler(
			ExecSessionRegistry execRegistry,
			AgentSessionRegistry sessionRegistry,
			List<AgentLifecycleListener> listeners,
			ClusterAgentProperties props) {
		return new PodExecWebSocketHandler(
				execRegistry, sessionRegistry, listeners, props.exec().bindTimeout());
	}

	// ---- gRPC ----

	@Bean
	@ConditionalOnMissingBean
	public AuthMetadataInterceptor authMetadataInterceptor() {
		return new AuthMetadataInterceptor();
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentRuntimeEndpoint agentRuntimeEndpoint(
			AgentIdentityAuthenticator authenticator,
			AgentSessionRegistry sessionRegistry,
			AgentIdentityStore identityStore,
			ExecSessionRegistry execSessionRegistry,
			LogStreamSessionRegistry logStreamSessionRegistry,
			List<AgentLifecycleListener> listeners,
			Clock clock) {
		return new AgentRuntimeEndpoint(
				authenticator, sessionRegistry, identityStore,
				execSessionRegistry, logStreamSessionRegistry, listeners, clock);
	}

	// ---- WebSocket config ----

	/**
	 * Worker mode (web-application-type=none) 에서는 ServletContext 없음 →
	 * ServletServerContainerFactoryBean 의 afterPropertiesSet 가 IllegalStateException. Servlet
	 * web 일 때만 활성. PodExecWebSocketHandler 등 비-bean dependency 는 worker 에서 사용 안 함.
	 *
	 * <p>{@code cluster-agent.exec.enabled=false} 로 PodExec WebSocket 기능 자체를 끌 가능. PodExec
	 * 안 쓰는 외부 consumer (gRPC API 만 사용) 가 ServletServerContainerFactoryBean 의 강제 생성을 피할 수
	 * 있도록. default true — 기존 동작 보존.
	 */
	@AutoConfiguration
	@EnableWebSocket
	@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
			type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
			prefix = "cluster-agent.exec", name = "enabled", matchIfMissing = true)
	static class WebSocketConfig implements WebSocketConfigurer {

		private final PodExecWebSocketHandler handler;
		private final ClusterAgentProperties props;

		WebSocketConfig(PodExecWebSocketHandler handler, ClusterAgentProperties props) {
			this.handler = handler;
			this.props = props;
		}

		@Override
		public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
			registry.addHandler(handler, props.exec().websocketPathPattern())
					.setAllowedOriginPatterns("*");
		}

		@Bean
		public ServletServerContainerFactoryBean createWebSocketContainer() {
			ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
			container.setMaxTextMessageBufferSize(props.exec().websocketBufferBytes());
			container.setMaxBinaryMessageBufferSize(props.exec().websocketBufferBytes());
			container.setMaxSessionIdleTimeout(0L);     // 터미널 idle 무한 — kubectl exec 패턴.
			return container;
		}
	}
}
