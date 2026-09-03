package io.aipaas.cluster.agent.observability.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.observability.core.ClusterCapabilities;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * capability 필터가 실제로 rule-set 을 걸러내는지 확인.
 *
 * <p>agent 세션이 없으므로 install 은 전부 실패로 끝난다 — 여기서 보는 것은 성공 여부가 아니라
 * "어떤 rule-set 이 시도 대상이 되었는가" 다.
 */
class AlertRuleInstallerCapabilityTest {

	private final AgentSessionRegistry sessionRegistry = Mockito.mock(AgentSessionRegistry.class);
	private final AlertRuleCatalog catalog = new AlertRuleCatalog();

	AlertRuleInstallerCapabilityTest() {
		// agent 가 없는 상태를 재현 — install 은 NO_ACTIVE_AGENT 로 실패하고 installAll 이 이를
		// 결과 항목으로 기록한다. 이 테스트가 보는 것은 성공 여부가 아니라 시도 대상 목록이다.
		Mockito.when(sessionRegistry.sendCommand(
						Mockito.anyString(), Mockito.any(), Mockito.anyInt()))
				.thenThrow(new AgentSessionRegistry.NoActiveSessionException("no session in test"));
	}

	private List<String> attemptedRuleSets(ClusterCapabilities capabilities) {
		AlertRuleInstaller installer = new AlertRuleInstaller(sessionRegistry, catalog, capabilities);
		return installer.installAll("c1", "monitoring", "kps", Duration.ofSeconds(1)).stream()
				.map(AlertRuleApplyResult::ruleSetId)
				.toList();
	}

	@Test
	void gpuRuleSetSkippedWhenClusterHasNoGpu() {
		// GPU 없는 cluster 에 깔면 DCGM 지표가 없어 절대 발화하지 않는 PrometheusRule 이 남는다.
		List<String> attempted = attemptedRuleSets(clusterName -> false);

		assertThat(attempted).doesNotContain("gpu");
		assertThat(attempted).contains("node", "pod");
	}

	@Test
	void gpuRuleSetAttemptedWhenClusterHasGpu() {
		List<String> attempted = attemptedRuleSets(clusterName -> true);

		assertThat(attempted).contains("gpu", "node", "pod");
	}

	@Test
	void allRuleSetsAttemptedWhenCapabilitySpiAbsent() {
		// SPI 미구현 호스트에서도 starter 는 동작해야 한다. 필터 없이 전체 설치.
		List<String> attempted = attemptedRuleSets(null);

		assertThat(attempted).containsAll(catalog.ids());
	}
}
