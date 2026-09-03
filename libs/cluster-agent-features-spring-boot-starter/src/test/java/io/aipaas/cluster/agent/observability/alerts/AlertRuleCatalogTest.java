package io.aipaas.cluster.agent.observability.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** classpath 의 alert-rules/*.yaml 7개를 모두 로드하고 placeholder 치환 동작을 확인. */
class AlertRuleCatalogTest {

	private static final List<String> EXPECTED_IDS = List.of(
			"control-plane", "controllers", "kube-node", "node",
			"pod", "prometheus-stack", "storage");

	@Test
	void loadsExpectedRuleSets() {
		AlertRuleCatalog catalog = new AlertRuleCatalog();
		assertThat(catalog.ids()).containsExactlyInAnyOrderElementsOf(EXPECTED_IDS);
	}

	@Test
	void everyRuleSetIsNonEmpty() {
		AlertRuleCatalog catalog = new AlertRuleCatalog();
		for (AlertRuleSet rs : catalog.list()) {
			assertThat(rs.id()).isNotBlank();
			assertThat(rs.manifestYaml()).contains("PrometheusRule");
			assertThat(rs.manifestYaml()).contains("${NAMESPACE}");
			assertThat(rs.manifestYaml()).contains("${RELEASE}");
			assertThat(rs.ruleCount())
					.as("rule-set %s must declare at least one alert", rs.id())
					.isGreaterThan(0);
		}
	}

	@Test
	void substituteReplacesPlaceholders() {
		String yaml = """
				metadata:
				  namespace: ${NAMESPACE}
				  labels:
				    release: ${RELEASE}
				""";
		String out = AlertRuleInstaller.substitute(yaml, "monitoring", "kps");
		assertThat(out).contains("namespace: monitoring");
		assertThat(out).contains("release: kps");
		assertThat(out).doesNotContain("${");
	}

	@Test
	void nodeRuleSetHasFiveAlerts() {
		// node.yaml 은 의도적으로 5개 alert (CPU/memory/disk/predictedFull/down). 회귀 방지용 hard check.
		AlertRuleCatalog catalog = new AlertRuleCatalog();
		AlertRuleSet node = catalog.byId("node").orElseThrow();
		assertThat(node.ruleCount()).isEqualTo(5);
	}
}
