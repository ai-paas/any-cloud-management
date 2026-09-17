package io.aipaas.cluster.provisioning.api;

import java.util.Map;

/** Pulumi 실행에 필요한 config 추상화 (SPI 포트). */
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
