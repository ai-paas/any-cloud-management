package io.aipaas.cluster.agent.observability.autoconfigure;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleCatalog;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleInstaller;
import io.aipaas.cluster.agent.observability.core.ClusterCapabilities;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import io.aipaas.cluster.agent.observability.core.DashboardSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.observability.dashboard.DashboardLocator;
import io.aipaas.cluster.agent.observability.metrics.ClusterMetricsService;
import io.aipaas.cluster.agent.observability.query.ObservabilityQueryService;
import io.aipaas.cluster.agent.observability.stack.DefaultDashboardImporter;
import io.aipaas.cluster.agent.observability.stack.GpuCapabilityHeartbeatListener;
import io.aipaas.cluster.agent.observability.stack.HelmReleaseLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Observability starter auto-config — 활성 조건: 호스트가 {@link ClusterCatalog} 제공. 모든 bean override 가능. */
@AutoConfiguration
@ConditionalOnClass(ObservabilityQueryService.class)
@ConditionalOnBean(ClusterCatalog.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ClusterObservabilityAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ObservabilityQueryService observabilityQueryService(
			AgentSessionRegistry sessionRegistry,
			ClusterCatalog catalog,
			ObservabilityProperties props) {
		return new ObservabilityQueryService(
				sessionRegistry, catalog, props.query().defaultTimeout());
	}

	@Bean
	@ConditionalOnMissingBean
	public ClusterMetricsService clusterMetricsService(
			ObservabilityQueryService queryService, ObjectMapper objectMapper) {
		return new ClusterMetricsService(queryService, objectMapper);
	}

	// install.timeout 설정은 props 에 보존 (다른 path 에서 활용).

	// ----- anycloud-default PrometheusRule 카탈로그 -----

	/**
	 * alert rule catalog / installer 의 per-feature kill-switch.
	 * {@code cluster-observability.alerts.enabled=false} 로 Catalog + Installer 일괄 비활성.
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-observability.alerts", name = "enabled", matchIfMissing = true)
	public AlertRuleCatalog alertRuleCatalog() {
		return new AlertRuleCatalog();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-observability.alerts", name = "enabled", matchIfMissing = true)
	public AlertRuleInstaller alertRuleInstaller(
			AgentSessionRegistry sessionRegistry,
			AlertRuleCatalog catalog,
			org.springframework.beans.factory.ObjectProvider<ClusterCapabilities> capabilities) {
		return new AlertRuleInstaller(sessionRegistry, catalog, capabilities.getIfAvailable());
	}

	@Bean
	@ConditionalOnMissingBean
	public DashboardLocator dashboardLocator(
			AgentSessionRegistry sessionRegistry, ObservabilityProperties props) {
		return new DashboardLocator(sessionRegistry, props.dashboard().timeout());
	}

	// GPU dcgm-exporter 는 cluster_addon row (type=GPU_EXPORTER) 로 install — MonitoringAddonInstaller
	// 의 hasGpuNodes 분기가 자동 동반 row 생성.

	@Bean
	@ConditionalOnMissingBean
	public HelmReleaseLookup helmReleaseLookup(
			AgentSessionRegistry sessionRegistry, ObservabilityProperties props) {
		return new HelmReleaseLookup(sessionRegistry, props.autoInstall().releaseLookupTimeout());
	}

	/**
	 * kube-prometheus-stack 의 Grafana sidecar 가 watching 하는 'monitoring' ns 로 dashboard import.
	 *
	 * <p>{@code cluster-observability.dashboards.enabled=false} 로 비활성 가능.
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-observability.dashboards", name = "enabled", matchIfMissing = true)
	public DefaultDashboardImporter defaultDashboardImporter(
			AgentSessionRegistry sessionRegistry, ObservabilityProperties props) {
		return new DefaultDashboardImporter(sessionRegistry, "monitoring");
	}

	// Install 흐름: frontend → POST /v1/clusters/{c}/addons → ClusterAddonEntity(PENDING) →
	// cluster ACTIVE 시 AddonOrchestrator enqueue → RabbitMqAddonInstallListener →
	// MonitoringAddonInstaller.

	/** Heartbeat gpu_node_count → capability sink backfill (sink bean 없으면 disable). */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(ClusterCapabilitiesSink.class)
	public GpuCapabilityHeartbeatListener gpuCapabilityHeartbeatListener(ClusterCapabilitiesSink sink) {
		return new GpuCapabilityHeartbeatListener(sink);
	}
}
