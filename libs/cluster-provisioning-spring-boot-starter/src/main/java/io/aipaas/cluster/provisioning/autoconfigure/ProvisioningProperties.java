package io.aipaas.cluster.provisioning.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cluster-provisioning starter 의 외부 설정.
 *
 * <p>application.yml prefix: {@code cluster-provisioning}.
 *
 * <p>state backup 설정(enabled/directory/retention 등)은 본 record 가 아니라
 * {@link io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider} SPI 로 host 가 공급한다.
 * backup scheduler/validator 의 cron schedule 만 별도로
 * {@code cluster-provisioning.state-backup[.restore-dry-run].cron} property 로 읽는다 (@Scheduled).
 */
@ConfigurationProperties(prefix = "cluster-provisioning")
public record ProvisioningProperties(Pulumi pulumi) {

	public ProvisioningProperties {
		if (pulumi == null) pulumi = new Pulumi(null, null, null, null);
	}

	public record Pulumi(
			String binaryPath,         // null → PATH 의 pulumi 사용. 명시 시 절대 경로 (Docker 이미지 등).
			String projectDir,         // null → caller working directory. infra/pulumi 등 명시 가능.
			String stateBackendUrl,    // s3://... — RustFS / S3. login 시 사용.
			Duration commandTimeout) { // pulumi up / preview 등의 timeout. default 30분.

		public Pulumi {
			if (commandTimeout == null) commandTimeout = Duration.ofMinutes(30);
		}
	}
}
