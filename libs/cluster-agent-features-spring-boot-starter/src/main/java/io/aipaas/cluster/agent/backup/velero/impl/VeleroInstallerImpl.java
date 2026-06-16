package io.aipaas.cluster.agent.backup.velero.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.HelmReleaseService.InstalledRelease;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyApplyResult;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroInstallResult;
import io.aipaas.cluster.agent.backup.velero.VeleroInstallSpec;
import io.aipaas.cluster.agent.backup.velero.VeleroInstaller;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * VeleroInstaller 구현.
 *
 * <p>cluster-agent-starter 의 {@link HelmReleaseService#install} 위에 build — proto 변경 없이
 * 기존 INSTALL_ADDON 명령을 그대로 활용. 본 클래스의 역할은 spec → Velero chart 의 values JSON 변환.
 */
@Slf4j
public class VeleroInstallerImpl implements VeleroInstaller {

	/** vmware-tanzu official helm chart repo. */
	private static final String CHART_REPO_URL = "https://vmware-tanzu.github.io/helm-charts";
	private static final String CHART_NAME = "velero/velero";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HelmReleaseService helmReleaseService;
	/** Velero 설치 직후 자동 정책 install 용. null 이면 hook 비활성. */
	private final BackupPolicyInstaller policyInstaller;
	private final boolean autoInstallPolicies;

	/** Legacy constructor — auto-install hook 없이 install only. 기존 test/host 호환. */
	public VeleroInstallerImpl(HelmReleaseService helmReleaseService) {
		this(helmReleaseService, null, false);
	}

	public VeleroInstallerImpl(HelmReleaseService helmReleaseService,
			BackupPolicyInstaller policyInstaller, boolean autoInstallPolicies) {
		this.helmReleaseService = helmReleaseService;
		this.policyInstaller = policyInstaller;
		this.autoInstallPolicies = autoInstallPolicies;
	}

	@Override
	public VeleroInstallResult install(String clusterName, VeleroInstallSpec spec) {
		if (spec == null || spec.provider() == null) {
			throw new BackupException("INVALID_PARAMS", "provider required");
		}
		if (spec.bucket() == null || spec.bucket().isBlank()) {
			throw new BackupException("INVALID_PARAMS", "bucket required");
		}

		String ns = orDefault(spec.namespace(), VeleroInstallSpec.DEFAULT_NAMESPACE);
		String release = orDefault(spec.releaseName(), VeleroInstallSpec.DEFAULT_RELEASE);
		String chartVersion = orDefault(spec.chartVersion(), VeleroInstallSpec.DEFAULT_CHART_VERSION);

		String valuesJson;
		try {
			valuesJson = MAPPER.writeValueAsString(buildValues(spec));
		} catch (JsonProcessingException e) {
			throw new BackupException("INVALID_PARAMS", "failed to serialize values: " + e.getMessage(), e);
		}

		log.info("Velero install cluster={} ns={} chart={} version={} provider={}",
				clusterName, ns, CHART_NAME, chartVersion, spec.provider());

		VeleroInstallResult installResult;
		try {
			InstalledRelease result = helmReleaseService.install(
					clusterName, release, CHART_NAME, CHART_REPO_URL,
					null,                       // chartTarballBase64 — 사용 안 함
					chartVersion, ns, valuesJson,
					true);                      // createNamespace
			installResult = new VeleroInstallResult(
					clusterName, result.release(), result.namespace(),
					result.version(), result.status());
		} catch (RuntimeException e) {
			throw new BackupException("VELERO_INSTALL_FAILED",
					"helm install velero failed: " + e.getMessage(), e);
		}

		// Velero 설치 성공 시 자동 정책 install. 정책 install 실패는 helm install 자체의 성공을 무효화하지 않는다 —
		// warn log 후 진행. host 가 후속으로 수동 install 가능.
		if (autoInstallPolicies && policyInstaller != null) {
			try {
				var results = policyInstaller.installAll(clusterName, ns);
				long ok = results.stream().filter(r -> "applied".equals(r.status())).count();
				log.info("Velero policy auto-install cluster={} applied={}/{}",
						clusterName, ok, results.size());
				if (ok < results.size()) {
					for (BackupPolicyApplyResult r : results) {
						if (!"applied".equals(r.status())) {
							log.warn("Velero policy {} status={} cluster={}",
									r.policyId(), r.status(), clusterName);
						}
					}
				}
			} catch (Exception e) {
				log.warn("Velero policy auto-install failed cluster={} (non-fatal): {}",
						clusterName, e.toString());
			}
		}

		return installResult;
	}

	/** Velero helm chart values 구성. provider 별로 BSL/VSL 와 credentials 채움. */
	@SuppressWarnings("unchecked")
	Map<String, Object> buildValues(VeleroInstallSpec spec) {
		Map<String, Object> values = new LinkedHashMap<>();

		// configuration.backupStorageLocation
		Map<String, Object> bslConfig = bslConfig(spec);
		Map<String, Object> bsl = new LinkedHashMap<>();
		bsl.put("name", "default");
		bsl.put("provider", bslProviderName(spec.provider()));
		bsl.put("bucket", spec.bucket());
		bsl.put("config", bslConfig);

		// configuration.volumeSnapshotLocation
		// CSI 사용 시 VSL 은 비워둠 (CSI snapshot driver 가 처리).
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("backupStorageLocation", List.of(bsl));
		if (!spec.csiSnapshots()) {
			Map<String, Object> vsl = new LinkedHashMap<>();
			vsl.put("name", "default");
			vsl.put("provider", bslProviderName(spec.provider()));
			vsl.put("config", vslConfig(spec));
			configuration.put("volumeSnapshotLocation", List.of(vsl));
		}
		if (spec.csiSnapshots()) {
			configuration.put("features", "EnableCSI");
		}
		values.put("configuration", configuration);

		// credentials
		Map<String, Object> credentials = new LinkedHashMap<>();
		credentials.put("useSecret", true);
		Map<String, String> secretContents = new LinkedHashMap<>();
		secretContents.put("cloud", credentialsBody(spec));
		credentials.put("secretContents", secretContents);
		values.put("credentials", credentials);

		// initContainers — provider 별 plugin.
		String pluginVer = orDefault(spec.pluginVersion(), "v1.10.0");
		Map<String, Object> plugin = new LinkedHashMap<>();
		plugin.put("name", "velero-plugin-for-" + pluginShortName(spec.provider()));
		plugin.put("image", "velero/velero-plugin-for-" + pluginShortName(spec.provider()) + ":" + pluginVer);
		plugin.put("volumeMounts", List.of(Map.of("mountPath", "/target", "name", "plugins")));
		values.put("initContainers", List.of(plugin));

		// node-agent (file-system backup, opt-in).
		if (spec.useNodeAgent()) {
			Map<String, Object> nodeAgent = new LinkedHashMap<>();
			nodeAgent.put("enabled", true);
			values.put("nodeAgent", nodeAgent);
		}

		// user override merge — top-level keys overlay.
		if (spec.additionalValues() != null && !spec.additionalValues().isEmpty()) {
			for (Map.Entry<String, Object> e : spec.additionalValues().entrySet()) {
				values.put(e.getKey(), e.getValue());
			}
		}

		return values;
	}

	/** BSL 의 `provider` 필드 (Velero 가 인식하는 이름). s3-compatible → aws (S3 API). */
	private static String bslProviderName(String provider) {
		String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
		return switch (p) {
			case "aws", "s3-compatible" -> "aws";
			case "gcp" -> "gcp";
			case "azure" -> "azure";
			default -> p;
		};
	}

	/** plugin 의 short name (image suffix). s3-compatible → aws (같은 플러그인). */
	private static String pluginShortName(String provider) {
		return bslProviderName(provider);
	}

	private static Map<String, Object> bslConfig(VeleroInstallSpec spec) {
		Map<String, Object> cfg = new LinkedHashMap<>();
		String p = spec.provider() == null ? "" : spec.provider().toLowerCase(Locale.ROOT);
		switch (p) {
			case "aws" -> cfg.put("region", spec.region() == null ? "us-east-1" : spec.region());
			case "s3-compatible" -> {
				cfg.put("region", spec.region() == null ? "minio" : spec.region());
				cfg.put("s3ForcePathStyle", "true");
				cfg.put("s3Url", spec.s3Url() == null ? "" : spec.s3Url());
			}
			case "gcp" -> { /* GCS 는 별도 config 없음 — credentials 만 */ }
			case "azure" -> {
				cfg.put("resourceGroup", "");                // host 가 additionalValues 로 명시 가능
				cfg.put("storageAccount", spec.azureStorageAccount() == null ? "" : spec.azureStorageAccount());
			}
			default -> { /* no extra */ }
		}
		return cfg;
	}

	private static Map<String, Object> vslConfig(VeleroInstallSpec spec) {
		Map<String, Object> cfg = new LinkedHashMap<>();
		if ("aws".equals(spec.provider()) || "s3-compatible".equals(spec.provider())) {
			cfg.put("region", spec.region() == null ? "us-east-1" : spec.region());
		}
		return cfg;
	}

	/** ~/.aws/credentials 또는 동등 형식의 plain-text. provider 별로 다름. */
	private static String credentialsBody(VeleroInstallSpec spec) {
		String p = spec.provider() == null ? "" : spec.provider().toLowerCase(Locale.ROOT);
		return switch (p) {
			case "aws", "s3-compatible" -> "[default]\n"
					+ "aws_access_key_id=" + nz(spec.accessKey()) + "\n"
					+ "aws_secret_access_key=" + nz(spec.secretKey()) + "\n";
			case "gcp" -> nz(spec.gcpServiceAccountJson());
			case "azure" -> "AZURE_STORAGE_ACCOUNT_ACCESS_KEY=" + nz(spec.azureStorageAccessKey()) + "\n";
			default -> "";
		};
	}

	private static String orDefault(String s, String fallback) {
		return (s == null || s.isBlank()) ? fallback : s;
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
