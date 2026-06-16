package io.aipaas.cluster.agent.backup.velero;

import java.util.Map;

/**
 * Velero helm install 의 spec.
 *
 * <p>본 record 는 host (anycloud) 가 CSP credential 시스템에서 resolve 한 결과를 채워 starter 에
 * 전달. starter 는 spec → helm values JSON 변환 후 cluster-agent 의 helm install 호출.
 *
 * <p>지원 BSL (BackupStorageLocation) provider:
 * <ul>
 *   <li>{@code aws} — AWS S3 (region 필수)</li>
 *   <li>{@code gcp} — Google Cloud Storage (서비스 계정 JSON 필요)</li>
 *   <li>{@code azure} — Azure Blob (계정 키 + 컨테이너)</li>
 *   <li>{@code s3-compatible} — MinIO / Wasabi / R2 등 (s3Url + force path style)</li>
 * </ul>
 *
 * <p>Note: VSL (VolumeSnapshotLocation) 도 같은 provider 로 자동 설정. CSI 환경에서는 VSL 대신
 * CSI snapshot 사용 (별도 plugin 불요) — 본 spec 의 {@code csiSnapshots=true} 로 활성.
 */
public record VeleroInstallSpec(
		String namespace,
		String releaseName,
		String chartVersion,

		// BSL/VSL
		String provider,
		String bucket,
		String region,

		// S3-compatible 만
		String s3Url,
		boolean s3ForcePathStyle,

		// credentials — provider 별로 의미 다름:
		//   aws / s3-compatible: accessKey/secretKey 사용
		//   gcp: gcpServiceAccountJson 사용
		//   azure: azureStorageAccount + azureStorageAccessKey 사용
		String accessKey,
		String secretKey,
		String gcpServiceAccountJson,
		String azureStorageAccount,
		String azureStorageAccessKey,

		// 옵션
		String pluginVersion,
		boolean csiSnapshots,
		boolean useNodeAgent,
		Map<String, Object> additionalValues) {

	public static final String DEFAULT_NAMESPACE = "velero";
	public static final String DEFAULT_RELEASE = "velero";
	public static final String DEFAULT_CHART_VERSION = "8.2.0";

	/** AWS S3 quick-constructor. */
	public static VeleroInstallSpec awsS3(String bucket, String region,
			String accessKey, String secretKey) {
		return new VeleroInstallSpec(
				DEFAULT_NAMESPACE, DEFAULT_RELEASE, DEFAULT_CHART_VERSION,
				"aws", bucket, region,
				null, false,
				accessKey, secretKey, null, null, null,
				"v1.10.0", true, false, Map.of());
	}

	/** S3-compatible (MinIO / Wasabi). */
	public static VeleroInstallSpec s3Compatible(String s3Url, String bucket, String region,
			String accessKey, String secretKey) {
		return new VeleroInstallSpec(
				DEFAULT_NAMESPACE, DEFAULT_RELEASE, DEFAULT_CHART_VERSION,
				"s3-compatible", bucket, region,
				s3Url, true,
				accessKey, secretKey, null, null, null,
				"v1.10.0", false, true, Map.of());
	}

	/** GCS quick-constructor. */
	public static VeleroInstallSpec gcs(String bucket, String gcpServiceAccountJson) {
		return new VeleroInstallSpec(
				DEFAULT_NAMESPACE, DEFAULT_RELEASE, DEFAULT_CHART_VERSION,
				"gcp", bucket, null,
				null, false,
				null, null, gcpServiceAccountJson, null, null,
				"v1.10.0", true, false, Map.of());
	}

	/** Azure Blob quick-constructor. */
	public static VeleroInstallSpec azure(String bucket, String storageAccount, String storageAccessKey) {
		return new VeleroInstallSpec(
				DEFAULT_NAMESPACE, DEFAULT_RELEASE, DEFAULT_CHART_VERSION,
				"azure", bucket, null,
				null, false,
				null, null, null, storageAccount, storageAccessKey,
				"v1.10.0", true, false, Map.of());
	}
}
