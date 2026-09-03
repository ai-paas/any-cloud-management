package io.aipaas.cluster.provisioning.api;

import java.util.Map;

/**
 * Pulumi 실행에 필요한 config 추상화 (SPI 포트). starter 의 {@link
 * io.aipaas.cluster.provisioning.internal.AutomationProvisioningService} 가 state backend /
 * passphrase / stack prefix 를 이 포트로 읽는다. host (anycloud {@code PulumiProperties}) 가
 * implements 하거나, 미등록 시 {@link io.aipaas.cluster.provisioning.autoconfigure.ProvisioningProperties}
 * 기반 기본 bean 사용.
 *
 * <p>메서드 이름은 Lombok {@code @Getter} 생성 시그니처와 일치 — host config 가 추가 메서드 없이
 * {@code implements} 만으로 만족.
 */
public interface ExecutionConfig {

	/** 모든 Pulumi 명령에 병합할 기본 환경 변수. */
	Map<String, String> getEnvironment();

	/** {@code PULUMI_CONFIG_PASSPHRASE} 값. null/blank 면 미설정. */
	String getPassphrase();

	/** {@code PULUMI_BACKEND_URL} 값 (self-hosted S3/RustFS). null/blank 면 Pulumi 기본. */
	String getBackendUrl();

	/** {@code stack init} 시 {@code --secrets-provider} 인자. null/blank 면 미전달. */
	String getSecretsProvider();

	/** provisioning 활성 여부. false 면 provision/preview/destroy 가 즉시 거부된다. */
	boolean isEnabled();

	/** stack 이름 prefix — {@code <prefix>-<provider>-<env>-<cluster>} 형식. */
	String getStackPrefix();
}
