package io.aipaas.cluster.agent.observability.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cluster-observability starter 의 동작 설정.
 *
 * <pre>{@code
 * cluster-observability:
 *   query:
 *     default-timeout: 5s          # PromQL/targets/alerts agent 호출 timeout
 *     fan-out-timeout: 8s          # multi-cluster queryAll 의 per-cluster timeout
 *   install:
 *     timeout: 10m                 # kube-prometheus-stack helm install 대기 시간
 *   dashboard:
 *     timeout: 3s                  # GET_DASHBOARD_URL 호출 timeout
 * }</pre>
 */
@ConfigurationProperties(prefix = "cluster-observability")
public record ObservabilityProperties(
		Query query, Install install, Dashboard dashboard, AutoInstall autoInstall) {

	public ObservabilityProperties {
		if (query == null) {
			query = new Query(Duration.ofSeconds(5), Duration.ofSeconds(8));
		}
		if (install == null) {
			install = new Install(Duration.ofMinutes(10));
		}
		if (dashboard == null) {
			dashboard = new Dashboard(Duration.ofSeconds(3));
		}
		if (autoInstall == null) {
			autoInstall = new AutoInstall(true, Duration.ofSeconds(5), true);
		}
	}

	public record Query(Duration defaultTimeout, Duration fanOutTimeout) {}

	public record Install(Duration timeout) {}

	public record Dashboard(Duration timeout) {}

	/**
	 * Agent ACTIVE 전환 시 자동으로 kube-prometheus-stack 설치 여부 + helper.
	 *
	 * @param enabled                자동 설치 활성. default true.
	 * @param releaseLookupTimeout   설치 전 LIST_HELM_RELEASES check timeout.
	 * @param defaultAlertRules      Stack 자동 설치 직후 anycloud-default PrometheusRule 카탈로그
	 *                               일괄 install. default true. 일부 실패해도 stack 설치 자체는 성공으로 treat.
	 */
	public record AutoInstall(boolean enabled, Duration releaseLookupTimeout, boolean defaultAlertRules) {
		public AutoInstall(boolean enabled, Duration releaseLookupTimeout) {
			this(enabled, releaseLookupTimeout, true);
		}
	}
}
