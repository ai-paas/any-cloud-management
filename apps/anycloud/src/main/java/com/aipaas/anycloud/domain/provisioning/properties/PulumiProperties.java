package com.aipaas.anycloud.domain.provisioning.properties;

import io.aipaas.cluster.provisioning.core.PulumiExecutionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * anycloud 의 Pulumi 설정 (prefix {@code pulumi}). cluster-provisioning starter 의
 * {@link PulumiExecutionConfig} 포트를 직접 구현하므로, starter 의 {@code PulumiCommandService} 기본
 * 구현이 이 bean 을 config 소스로 사용한다 (config 키는 호환 유지). 추가로 anycloud 도메인 전용
 * 필드(sshUser / stackPrefix / organization / runtimeDir 등)도 보유.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pulumi")
public class PulumiProperties implements PulumiExecutionConfig {

    private boolean enabled = false;
    private String binaryPath = "pulumi";
    private String projectDir = "infra/pulumi";
    private String runtimeDir = "runtime/pulumi";
    private String stackPrefix = "anycloud";
    private String organization = "local";
    private boolean autoCreateStack = true;
    private String passphrase;
    private String sshUser = "ubuntu";

    /**
     * Pulumi state backend URL. Self-hosted 환경에서는 S3-compatible storage
     * (예: RustFS / MinIO / Ceph RGW)를 가리킨다.
     * <p>
     * 예: {@code s3://pulumi-state?endpoint=https://rustfs:9000&s3ForcePathStyle=true}
     * <p>
     * 비어 있으면 Pulumi 의 기본 동작(로컬 파일 또는 Pulumi Cloud)을 따른다.
     */
    private String backendUrl;

    /**
     * Pulumi secrets provider. self-hosted 옵션:
     * <ul>
     *   <li>{@code passphrase} — {@code PULUMI_CONFIG_PASSPHRASE} 환경변수만으로 동작</li>
     *   <li>{@code hashivault://<host>/<key>} — HashiCorp Vault 또는 호환 구현(OpenBao)</li>
     * </ul>
     * 비어 있으면 Pulumi 기본값. stack init 시 {@code --secrets-provider} 인자로 전달된다.
     */
    private String secretsProvider;

    private Map<String, String> environment = new HashMap<>();

    public Path resolveProjectDir() {
        return Paths.get(projectDir).toAbsolutePath().normalize();
    }

    public Path resolveRuntimeDir() {
        return Paths.get(runtimeDir).toAbsolutePath().normalize();
    }
}
