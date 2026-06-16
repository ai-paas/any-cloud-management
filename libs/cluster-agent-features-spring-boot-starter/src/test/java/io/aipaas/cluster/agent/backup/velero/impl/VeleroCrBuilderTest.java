package io.aipaas.cluster.agent.backup.velero.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.backup.velero.VeleroBackupRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroInstallSpec;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreRequest;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CR build 함수가 올바른 K8s CR 구조를 생성하는지 확인. APPLY_MANIFEST dispatch 는
 * AgentSessionRegistry mock 없이 검증 불가 — 본 test 는 CR map 생성 로직만 격리 테스트.
 *
 * <p>각 build* 메서드는 package-private 으로 노출되어 있어 같은 package 의 test 가 직접 호출 가능.
 */
class VeleroCrBuilderTest {

	@Test
	void backupCrHasCorrectKindAndApiVersion() {
		var req = VeleroBackupRequest.fullCluster("backup-2026-05-24");
		Map<String, Object> cr = VeleroBackupServiceImpl.buildBackupCr(req, "velero");

		assertThat(cr.get("apiVersion")).isEqualTo("velero.io/v1");
		assertThat(cr.get("kind")).isEqualTo("Backup");
		@SuppressWarnings("unchecked")
		Map<String, Object> metadata = (Map<String, Object>) cr.get("metadata");
		assertThat(metadata.get("name")).isEqualTo("backup-2026-05-24");
		assertThat(metadata.get("namespace")).isEqualTo("velero");
	}

	@Test
	void backupCrIncludesTtlAndSnapshotVolumes() {
		var req = new VeleroBackupRequest(
				"b1", "velero",
				List.of("prod"), List.of(), List.of(),
				Duration.ofHours(168), true, "default", null);
		Map<String, Object> cr = VeleroBackupServiceImpl.buildBackupCr(req, "velero");
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) cr.get("spec");

		assertThat(spec).containsEntry("ttl", "168h0m0s");
		assertThat(spec).containsEntry("snapshotVolumes", true);
		assertThat(spec).containsEntry("includedNamespaces", List.of("prod"));
		// excludedNamespaces 가 비어있어 omit 되어야 함.
		assertThat(spec).doesNotContainKey("excludedNamespaces");
	}

	@Test
	void labelSelectorIsParsed() {
		var req = new VeleroBackupRequest(
				"b", "velero", List.of(), List.of(), List.of(),
				null, true, "default", "app=foo,tier=db");
		Map<String, Object> cr = VeleroBackupServiceImpl.buildBackupCr(req, "velero");
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) cr.get("spec");
		@SuppressWarnings("unchecked")
		Map<String, Object> selector = (Map<String, Object>) spec.get("labelSelector");
		@SuppressWarnings("unchecked")
		Map<String, String> matchLabels = (Map<String, String>) selector.get("matchLabels");
		assertThat(matchLabels).containsEntry("app", "foo").containsEntry("tier", "db");
	}

	@Test
	void restoreCrRequiresBackupName() {
		var req = VeleroRestoreRequest.fromBackup("restore-1", "backup-2026-05-24");
		Map<String, Object> cr = VeleroRestoreServiceImpl.buildRestoreCr(req, "velero");

		assertThat(cr.get("kind")).isEqualTo("Restore");
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) cr.get("spec");
		assertThat(spec).containsEntry("backupName", "backup-2026-05-24");
		assertThat(spec).containsEntry("restorePVs", true);
	}

	@Test
	void restoreCrIncludesNamespaceMapping() {
		var req = VeleroRestoreRequest.withMapping("r", "b",
				Map.of("prod", "prod-restore"));
		Map<String, Object> cr = VeleroRestoreServiceImpl.buildRestoreCr(req, "velero");
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) cr.get("spec");
		@SuppressWarnings("unchecked")
		Map<String, String> mapping = (Map<String, String>) spec.get("namespaceMapping");
		assertThat(mapping).containsEntry("prod", "prod-restore");
	}

	@Test
	void scheduleCrEmbedsBackupTemplate() {
		var req = VeleroScheduleRequest.dailyFull("daily-full", "0 2 * * *");
		Map<String, Object> cr = VeleroScheduleServiceImpl.buildScheduleCr(req, "velero");

		assertThat(cr.get("kind")).isEqualTo("Schedule");
		@SuppressWarnings("unchecked")
		Map<String, Object> spec = (Map<String, Object>) cr.get("spec");
		assertThat(spec).containsEntry("schedule", "0 2 * * *");
		// Schedule.template 은 Backup spec 만 포함 (kind/apiVersion 없음).
		@SuppressWarnings("unchecked")
		Map<String, Object> tmpl = (Map<String, Object>) spec.get("template");
		assertThat(tmpl).containsKey("snapshotVolumes");
		assertThat(tmpl).doesNotContainKey("apiVersion");   // template 은 spec 만.
		assertThat(tmpl).doesNotContainKey("kind");
	}

	@Test
	void awsVeleroValuesHaveCorrectBslAndPlugin() {
		var spec = VeleroInstallSpec.awsS3("my-bucket", "us-east-1", "AKIA...", "secret");
		var installer = new VeleroInstallerImpl(null);   // helm service not invoked by buildValues
		Map<String, Object> values = installer.buildValues(spec);

		@SuppressWarnings("unchecked")
		Map<String, Object> config = (Map<String, Object>) values.get("configuration");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> bsl = (List<Map<String, Object>>) config.get("backupStorageLocation");
		assertThat(bsl).hasSize(1);
		assertThat(bsl.get(0)).containsEntry("provider", "aws");
		assertThat(bsl.get(0)).containsEntry("bucket", "my-bucket");
		// CSI snapshots true → VSL skip + features=EnableCSI.
		assertThat(config).doesNotContainKey("volumeSnapshotLocation");
		assertThat(config).containsEntry("features", "EnableCSI");

		// credentials cloud body should contain AWS access key.
		@SuppressWarnings("unchecked")
		Map<String, Object> creds = (Map<String, Object>) values.get("credentials");
		@SuppressWarnings("unchecked")
		Map<String, String> secretContents = (Map<String, String>) creds.get("secretContents");
		assertThat(secretContents.get("cloud")).contains("aws_access_key_id=AKIA");
		assertThat(secretContents.get("cloud")).contains("aws_secret_access_key=secret");

		// initContainers 에 plugin.
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> initContainers = (List<Map<String, Object>>) values.get("initContainers");
		assertThat(initContainers).hasSize(1);
		assertThat(initContainers.get(0).get("name")).isEqualTo("velero-plugin-for-aws");
	}

	@Test
	void minioVeleroValuesAddS3ForcePathStyle() {
		var spec = VeleroInstallSpec.s3Compatible("http://minio:9000", "backups", "minio", "key", "secret");
		var installer = new VeleroInstallerImpl(null);
		Map<String, Object> values = installer.buildValues(spec);

		@SuppressWarnings("unchecked")
		Map<String, Object> config = (Map<String, Object>) values.get("configuration");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> bsl = (List<Map<String, Object>>) config.get("backupStorageLocation");
		@SuppressWarnings("unchecked")
		Map<String, Object> bslConfig = (Map<String, Object>) bsl.get(0).get("config");
		assertThat(bslConfig).containsEntry("s3ForcePathStyle", "true");
		assertThat(bslConfig).containsEntry("s3Url", "http://minio:9000");
	}
}
