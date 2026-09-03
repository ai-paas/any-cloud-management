package io.aipaas.cluster.agent.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleCatalog;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleInstaller;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import io.aipaas.cluster.agent.observability.dashboard.DashboardLocator;
import io.aipaas.cluster.agent.observability.metrics.ClusterMetricsService;
import io.aipaas.cluster.agent.observability.query.ObservabilityQueryService;
import io.aipaas.cluster.agent.observability.stack.DefaultDashboardImporter;
import io.aipaas.cluster.agent.observability.stack.GpuCapabilityHeartbeatListener;
import io.aipaas.cluster.agent.observability.stack.HelmReleaseLookup;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link ClusterObservabilityAutoConfiguration} wiring 회귀.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>ClusterCatalog 가 있으면 모든 observability bean 자동 등록.</li>
 *   <li>{@code cluster-observability.{alerts,dashboards}.enabled=false} toggle.</li>
 *   <li>GpuCapabilityHeartbeatListener 는 ClusterCapabilitiesSink bean 부재 시 미생성.</li>
 *   <li>ClusterCatalog bean 부재 시 (ConditionalOnBean) 전체 wiring skip.</li>
 * </ul>
 */
class ClusterObservabilityAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					JacksonAutoConfiguration.class,
					ClusterObservabilityAutoConfiguration.class));

	private ApplicationContextRunner withInfra() {
		// Observability 는 agent-starter 의 AgentSessionRegistry + ClusterCatalog SPI 의존.
		ClusterCatalog emptyCatalog = () -> List.of();
		return runner
				.withBean(AgentSessionRegistry.class, () -> mock(AgentSessionRegistry.class))
				.withBean(ClusterCatalog.class, () -> emptyCatalog);
	}

	@Test
	void defaultBeansRegistered_whenInfraPresent() {
		withInfra().run(ctx -> assertThat(ctx)
				.hasSingleBean(ObservabilityQueryService.class)
				.hasSingleBean(ClusterMetricsService.class)
				.hasSingleBean(AlertRuleCatalog.class)
				.hasSingleBean(AlertRuleInstaller.class)
				.hasSingleBean(DashboardLocator.class)
				.hasSingleBean(HelmReleaseLookup.class)
				.hasSingleBean(DefaultDashboardImporter.class));
	}

	@Test
	void wiringSkipped_whenNoClusterCatalogBean() {
		// ConditionalOnBean(ClusterCatalog) 미충족 → 전체 AutoConfiguration skip.
		runner.withBean(AgentSessionRegistry.class, () -> mock(AgentSessionRegistry.class))
				.run(ctx -> assertThat(ctx)
						.doesNotHaveBean(ObservabilityQueryService.class)
						.doesNotHaveBean(AlertRuleCatalog.class));
	}

	@Test
	void alertsDisabled_skipsAlertCatalogAndInstaller() {
		withInfra()
				.withPropertyValues("cluster-observability.alerts.enabled=false")
				.run(ctx -> assertThat(ctx)
						.doesNotHaveBean(AlertRuleCatalog.class)
						.doesNotHaveBean(AlertRuleInstaller.class)
						// 다른 bean 은 영향 없음.
						.hasSingleBean(ObservabilityQueryService.class)
						.hasSingleBean(DefaultDashboardImporter.class));
	}

	@Test
	void dashboardsDisabled_skipsDashboardImporter() {
		withInfra()
				.withPropertyValues("cluster-observability.dashboards.enabled=false")
				.run(ctx -> assertThat(ctx)
						.doesNotHaveBean(DefaultDashboardImporter.class)
						.hasSingleBean(DashboardLocator.class)
						.hasSingleBean(AlertRuleCatalog.class));
	}

	@Test
	void gpuHeartbeatListener_skippedWithoutCapabilitiesSink() {
		// ConditionalOnBean(ClusterCapabilitiesSink) → host 가 capability sink 등록 안 하면 skip.
		withInfra().run(ctx -> assertThat(ctx).doesNotHaveBean(GpuCapabilityHeartbeatListener.class));
	}

	@Test
	void gpuHeartbeatListener_createdWhenSinkPresent() {
		ClusterCapabilitiesSink sink = mock(ClusterCapabilitiesSink.class);
		withInfra().withBean(ClusterCapabilitiesSink.class, () -> sink).run(ctx ->
				assertThat(ctx).hasSingleBean(GpuCapabilityHeartbeatListener.class));
	}

	@Test
	void hostOverridesAlertRuleCatalog_replacesDefault() {
		AlertRuleCatalog custom = new AlertRuleCatalog();
		withInfra().withBean(AlertRuleCatalog.class, () -> custom).run(ctx ->
				assertThat(ctx.getBean(AlertRuleCatalog.class)).isSameAs(custom));
	}
}
