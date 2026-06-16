package io.aipaas.cluster.provisioning.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * Pulumi CLI 실행에 필요한 config 추상화 (SPI 포트).
 *
 * <p>starter 의 {@code PulumiCommandService} 구현이 바이너리 경로 / project 디렉토리 / 환경 변수 /
 * state backend / passphrase 등을 이 포트로 읽는다. host 는 자신의 설정 소스를 이 인터페이스로 노출한다 —
 * 예: anycloud 의 {@code PulumiProperties}(prefix {@code pulumi})가 그대로 구현하므로 config 키 변경이
 * 필요 없다. host 가 bean 을 등록하지 않으면 starter 가 {@code ProvisioningProperties} 기반 기본값을 쓴다.
 *
 * <p>메서드 이름은 Lombok {@code @Getter} 가 생성하는 시그니처와 일치시켜, host config 클래스가 추가
 * 메서드 없이 {@code implements} 만으로 만족하도록 설계했다.
 */
public interface PulumiExecutionConfig {

	/** Pulumi 바이너리 경로. {@code "pulumi"}(PATH) 또는 절대 경로(Docker 이미지 등). */
	String getBinaryPath();

	/** Pulumi project 디렉토리(Pulumi.yaml 위치)의 절대 경로. */
	Path resolveProjectDir();

	/** 모든 Pulumi 명령에 병합할 기본 환경 변수. */
	Map<String, String> getEnvironment();

	/** {@code PULUMI_CONFIG_PASSPHRASE} 값. null/blank 면 미설정. */
	String getPassphrase();

	/** {@code PULUMI_BACKEND_URL} 값 (self-hosted S3/RustFS). null/blank 면 Pulumi 기본. */
	String getBackendUrl();

	/** {@code selectOrCreateStack} 에서 stack 부재 시 자동 {@code stack init} 수행 여부. */
	boolean isAutoCreateStack();

	/** {@code stack init} 시 {@code --secrets-provider} 인자. null/blank 면 미전달. */
	String getSecretsProvider();

	/** provisioning 활성 여부. false 면 provision/preview/destroy 가 즉시 거부된다. */
	boolean isEnabled();

	/** stack 이름 prefix — {@code <prefix>-<provider>-<env>-<cluster>} 형식. */
	String getStackPrefix();
}
