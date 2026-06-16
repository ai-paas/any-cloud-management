package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero helm install.
 *
 * <p>cluster-agent 의 {@code INSTALL_ADDON} command 를 통해 vmware-tanzu/velero helm chart 를
 * cluster 에 설치. credentials 는 spec 에 plaintext 로 전달되며 cluster-agent 가 K8s Secret 으로
 * 생성한다. caller (host) 는 storage credential 을 자체 secret store (Vault / KMS) 에서 resolve
 * 한 직후 본 메서드에 전달, 메모리에 오래 보유하지 말 것.
 *
 * <p>provider 지원: aws, gcp, azure, s3-compatible. 그 외 provider 는 starter SPI 확장 또는 helm
 * additionalValues 로 override.
 */
public interface VeleroInstaller {

	/**
	 * Velero 설치. 이미 설치된 cluster 에 다시 호출하면 helm upgrade-or-install 동작 (helm 의 기본).
	 *
	 * @param clusterName 대상 cluster
	 * @param spec        BSL/VSL/credentials 채워진 spec
	 * @return install 결과
	 * @throws io.aipaas.cluster.agent.backup.core.BackupException 실패 시 (error code 로 분기)
	 */
	VeleroInstallResult install(String clusterName, VeleroInstallSpec spec);
}
