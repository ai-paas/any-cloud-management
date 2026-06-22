package io.aipaas.cluster.provisioning.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cluster-provisioning starter 의 외부 설정.
 *
 * <p>application.yml prefix: {@code cluster-provisioning}.
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
