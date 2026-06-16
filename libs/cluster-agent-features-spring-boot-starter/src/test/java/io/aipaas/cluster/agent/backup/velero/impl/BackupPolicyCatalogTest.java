package io.aipaas.cluster.agent.backup.velero.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.backup.velero.BackupPolicy;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** bundled velero-policies/*.yaml 3개가 모두 로드되는지 + placeholder 치환 검증. */
class BackupPolicyCatalogTest {

	private static final List<String> EXPECTED_IDS = List.of(
			"daily-full-cluster", "hourly-workloads", "weekly-pv-snapshots");

	@Test
	void loadsExpectedPolicies() {
		BackupPolicyCatalog catalog = new BackupPolicyCatalog();
		assertThat(catalog.ids()).containsExactlyInAnyOrderElementsOf(EXPECTED_IDS);
	}

	@Test
	void everyPolicyHasScheduleAndTtl() {
		BackupPolicyCatalog catalog = new BackupPolicyCatalog();
		for (BackupPolicy p : catalog.list()) {
			assertThat(p.id()).isNotBlank();
			assertThat(p.manifestYaml()).contains("kind: Schedule");
			assertThat(p.manifestYaml()).contains("${NAMESPACE}");
			assertThat(p.schedule()).isNotBlank();
			assertThat(p.ttl()).isNotBlank();
		}
	}

	@Test
	void dailyScheduleIs02Utc() {
		BackupPolicyCatalog catalog = new BackupPolicyCatalog();
		BackupPolicy daily = catalog.byId("daily-full-cluster").orElseThrow();
		assertThat(daily.schedule()).contains("0 2 * * *");
		assertThat(daily.ttl()).isEqualTo("720h0m0s");
	}

	@Test
	void substituteReplacesPlaceholder() {
		String yaml = "metadata:\n  namespace: ${NAMESPACE}\n";
		String out = BackupPolicyInstallerImpl.substitute(yaml, "velero-prod");
		assertThat(out).contains("namespace: velero-prod");
		assertThat(out).doesNotContain("${NAMESPACE}");
	}

	@Test
	void hourlyExcludesSystemNamespaces() {
		BackupPolicyCatalog catalog = new BackupPolicyCatalog();
		BackupPolicy hourly = catalog.byId("hourly-workloads").orElseThrow();
		assertThat(hourly.manifestYaml())
				.contains("kube-system")
				.contains("velero")
				.contains("aipaas-system");
	}
}
