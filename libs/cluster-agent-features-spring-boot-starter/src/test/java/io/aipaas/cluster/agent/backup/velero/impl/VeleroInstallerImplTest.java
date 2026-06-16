package io.aipaas.cluster.agent.backup.velero.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.HelmReleaseService.InstalledRelease;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyApplyResult;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroInstallResult;
import io.aipaas.cluster.agent.backup.velero.VeleroInstallSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@link VeleroInstallerImpl} install path 회귀.
 *
 * <p>HelmReleaseService 를 mock 으로 주입 — VeleroInstallSpec → helm INSTALL_ADDON 호출 인자 변환 +
 * 응답 매핑 + auto-install policies hook 의 dispatch 확인. 실제 K8s 호출 없음.
 */
@ExtendWith(MockitoExtension.class)
class VeleroInstallerImplTest {

	@Mock
	private HelmReleaseService helmReleaseService;

	@Mock
	private BackupPolicyInstaller policyInstaller;

	private InstalledRelease helmOk;

	@BeforeEach
	void setUp() {
		helmOk = new InstalledRelease("velero", "velero", "velero/velero", "8.2.0", 1, "deployed");
	}

	@Test
	void install_awsS3_dispatchesHelmWithChartCoordinates() {
		// awsS3 quick-constructor → provider=aws, csi=true, namespace=velero, chart 8.2.0.
		var spec = VeleroInstallSpec.awsS3("anycloud-backup", "us-east-1", "AKIA...", "secret");
		when(helmReleaseService.install(
				eq("c1"), eq("velero"), eq("velero/velero"),
				eq("https://vmware-tanzu.github.io/helm-charts"),
				any(), eq("8.2.0"), eq("velero"), any(), eq(true)))
				.thenReturn(helmOk);

		var installer = new VeleroInstallerImpl(helmReleaseService);
		VeleroInstallResult result = installer.install("c1", spec);

		assertThat(result.clusterName()).isEqualTo("c1");
		assertThat(result.releaseName()).isEqualTo("velero");
		assertThat(result.namespace()).isEqualTo("velero");
		assertThat(result.status()).isEqualTo("deployed");

		// values JSON 안에 BSL 의 bucket / region / provider 포함되었는지.
		ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
		verify(helmReleaseService).install(
				eq("c1"), eq("velero"), eq("velero/velero"), any(),
				any(), eq("8.2.0"), eq("velero"),
				values.capture(), eq(true));
		assertThat(values.getValue())
				.contains("anycloud-backup")
				.contains("us-east-1")
				.contains("\"provider\":\"aws\"")
				.contains("AKIA")
				.contains("EnableCSI");
	}

	@Test
	void install_minioS3Compatible_includesS3ForcePathStyle() {
		var spec = VeleroInstallSpec.s3Compatible(
				"http://minio.aipaas.svc:9000", "backups", "minio", "key", "secret");
		when(helmReleaseService.install(
				any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
				.thenReturn(helmOk);

		new VeleroInstallerImpl(helmReleaseService).install("c2", spec);

		ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
		verify(helmReleaseService).install(any(), any(), any(), any(),
				any(), any(), any(), values.capture(), anyBoolean());
		assertThat(values.getValue())
				.contains("s3ForcePathStyle")
				.contains("http://minio.aipaas.svc:9000")
				// s3-compatible 은 csiSnapshots=false 의 기본값 → VSL 생성 + features 없음.
				.contains("volumeSnapshotLocation")
				.doesNotContain("EnableCSI");
	}

	@Test
	void install_gcs_useGcpServiceAccountJsonAsCredentials() {
		var spec = VeleroInstallSpec.gcs("backup-bucket", "{\"type\":\"service_account\"}");
		when(helmReleaseService.install(any(), any(), any(), any(), any(),
				any(), any(), any(), anyBoolean())).thenReturn(helmOk);

		new VeleroInstallerImpl(helmReleaseService).install("c3", spec);

		ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
		verify(helmReleaseService).install(any(), any(), any(), any(),
				any(), any(), any(), values.capture(), anyBoolean());
		assertThat(values.getValue())
				.contains("velero-plugin-for-gcp")
				.contains("service_account");
	}

	@Test
	void install_missingProvider_throwsInvalidParams() {
		var bad = new VeleroInstallSpec(null, null, null,
				null, "bucket", null,
				null, false,
				null, null, null, null, null,
				null, false, false, Map.of());

		var installer = new VeleroInstallerImpl(helmReleaseService);
		assertThatThrownBy(() -> installer.install("c1", bad))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode()).isEqualTo("INVALID_PARAMS"));
		verify(helmReleaseService, never()).install(any(), any(), any(), any(),
				any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void install_blankBucket_throwsInvalidParams() {
		var bad = new VeleroInstallSpec(null, null, null,
				"aws", "  ", "us-east-1",
				null, false,
				"k", "s", null, null, null,
				null, true, false, Map.of());

		assertThatThrownBy(() -> new VeleroInstallerImpl(helmReleaseService).install("c1", bad))
				.isInstanceOf(BackupException.class)
				.hasMessageContaining("bucket required");
	}

	@Test
	void install_helmFailure_wrapsInVeleroInstallFailed() {
		var spec = VeleroInstallSpec.awsS3("bucket", "us-east-1", "k", "s");
		when(helmReleaseService.install(any(), any(), any(), any(), any(),
				any(), any(), any(), anyBoolean()))
				.thenThrow(new RuntimeException("helm 500"));

		assertThatThrownBy(() -> new VeleroInstallerImpl(helmReleaseService).install("c1", spec))
				.isInstanceOf(BackupException.class)
				.satisfies(e -> assertThat(((BackupException) e).errorCode())
						.isEqualTo("VELERO_INSTALL_FAILED"));
	}

	@Test
	void install_autoInstallPoliciesTrue_invokesPolicyInstaller() {
		var spec = VeleroInstallSpec.awsS3("bucket", "us-east-1", "k", "s");
		when(helmReleaseService.install(any(), any(), any(), any(), any(),
				any(), any(), any(), anyBoolean())).thenReturn(helmOk);
		when(policyInstaller.installAll(eq("c1"), eq("velero"))).thenReturn(List.of(
				new BackupPolicyApplyResult("c1", "daily-full-cluster", "velero",
						"anycloud-daily-full-cluster", "applied"),
				new BackupPolicyApplyResult("c1", "hourly-workloads", "velero",
						"anycloud-hourly-workloads", "applied")));

		var installer = new VeleroInstallerImpl(helmReleaseService, policyInstaller, true);
		installer.install("c1", spec);

		verify(policyInstaller, times(1)).installAll("c1", "velero");
	}

	@Test
	void install_autoInstallDisabled_skipsPolicyInstaller() {
		var spec = VeleroInstallSpec.awsS3("bucket", "us-east-1", "k", "s");
		when(helmReleaseService.install(any(), any(), any(), any(), any(),
				any(), any(), any(), anyBoolean())).thenReturn(helmOk);

		var installer = new VeleroInstallerImpl(helmReleaseService, policyInstaller, false);
		installer.install("c1", spec);

		verify(policyInstaller, never()).installAll(any(), any());
	}

	@Test
	void install_policyInstallerThrows_isNonFatal() {
		// 정책 install 실패는 helm install 성공을 무효화하지 않는다 (warn 후 진행).
		var spec = VeleroInstallSpec.awsS3("bucket", "us-east-1", "k", "s");
		when(helmReleaseService.install(any(), any(), any(), any(), any(),
				any(), any(), any(), anyBoolean())).thenReturn(helmOk);
		when(policyInstaller.installAll(any(), any()))
				.thenThrow(new RuntimeException("agent down"));

		var installer = new VeleroInstallerImpl(helmReleaseService, policyInstaller, true);
		VeleroInstallResult result = installer.install("c1", spec);

		assertThat(result.status()).isEqualTo("deployed");
	}
}
