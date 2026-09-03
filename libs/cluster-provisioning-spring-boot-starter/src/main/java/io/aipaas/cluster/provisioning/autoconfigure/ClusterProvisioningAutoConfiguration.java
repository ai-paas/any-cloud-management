package io.aipaas.cluster.provisioning.autoconfigure;

import io.aipaas.cluster.provisioning.internal.AutomationProvisioningService;
import io.aipaas.cluster.provisioning.api.ExecutionConfig;
import io.aipaas.cluster.provisioning.program.ProvisionerOrchestrator;
import io.aipaas.cluster.provisioning.program.provisioner.AlibabaProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.AwsProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.AzureProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.DigitalOceanProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.GcpProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.OciProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.OpenstackProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.ProviderProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.ProviderRegistry;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * cluster-provisioning starter auto-config.
 *
 * <p>Pulumi Automation Java SDK 기반 in-JVM 오케스트레이션. CLI shell-out 모두 제거 — Pulumi binary
 * 는 여전히 필요하지만 ProcessBuilder fork 없이 Automation API 가 invoke. Go runtime 의존성 0.
 *
 * <p>kill-switch: {@code cluster-provisioning.enabled=false} → autoConfig 전체 비활성.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ProvisioningProperties.class)
@ConditionalOnProperty(prefix = "cluster-provisioning", name = "enabled", matchIfMissing = true)
public class ClusterProvisioningAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.internal.ProvisionEventBus provisionEventBus() {
		return new io.aipaas.cluster.provisioning.internal.ProvisionEventBus();
	}

	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.internal.EngineEventAdapter engineEventAdapter(
			io.aipaas.cluster.provisioning.internal.ProvisionEventBus eventBus) {
		return new io.aipaas.cluster.provisioning.internal.EngineEventAdapter(eventBus);
	}

	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.internal.ProvisioningResultMapper provisioningResultMapper(
			jakarta.validation.Validator validator) {
		return new io.aipaas.cluster.provisioning.internal.ProvisioningResultMapper(validator);
	}

	/**
	 * Pulumi 실행 config 포트 default — {@link ProvisioningProperties} 의 {@code pulumi.*} 값 매핑.
	 * host 가 자체 {@link ExecutionConfig} bean 을 등록하면 override.
	 */
	@Bean
	@ConditionalOnMissingBean
	public ExecutionConfig defaultExecutionConfig(ProvisioningProperties properties) {
		ProvisioningProperties.Pulumi pulumi = properties.pulumi();
		return new ExecutionConfig() {
			@Override
			public Map<String, String> getEnvironment() {
				return Map.of();
			}

			@Override
			public String getPassphrase() {
				return null;
			}

			@Override
			public String getBackendUrl() {
				return pulumi.stateBackendUrl();
			}

			@Override
			public String getSecretsProvider() {
				return null;
			}

			@Override
			public boolean isEnabled() {
				return true;
			}

			@Override
			public String getStackPrefix() {
				return "cluster";
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(name = "awsProvisioner")
	public AwsProvisioner awsProvisioner() {
		return new AwsProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "gcpProvisioner")
	public GcpProvisioner gcpProvisioner() {
		return new GcpProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "azureProvisioner")
	public AzureProvisioner azureProvisioner() {
		return new AzureProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "ociProvisioner")
	public OciProvisioner ociProvisioner() {
		return new OciProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "alibabaProvisioner")
	public AlibabaProvisioner alibabaProvisioner() {
		return new AlibabaProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "digitalOceanProvisioner")
	public DigitalOceanProvisioner digitalOceanProvisioner() {
		return new DigitalOceanProvisioner();
	}

	@Bean
	@ConditionalOnMissingBean(name = "openstackProvisioner")
	public OpenstackProvisioner openstackProvisioner() {
		return new OpenstackProvisioner();
	}

	/** ProviderProvisioner bean 모음 → ProviderRegistry. ProvisionerOrchestrator 의 dispatch. */
	@Bean
	@ConditionalOnMissingBean
	public ProviderRegistry providerRegistry(List<ProviderProvisioner> provisioners) {
		return new ProviderRegistry(provisioners);
	}

	/** Pulumi 프로그램 default — ProviderRegistry 로 dispatch. */
	@Bean
	@ConditionalOnMissingBean
	public ProvisionerOrchestrator provisionerOrchestrator(ProviderRegistry registry) {
		return new ProvisionerOrchestrator(registry);
	}

	/**
	 * {@link io.aipaas.cluster.provisioning.api.ProvisioningService} default impl —
	 * Pulumi Automation Java SDK 기반 in-JVM 구현.
	 */
	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.api.ProvisioningService provisioningService(
			ExecutionConfig config,
			ProvisionerOrchestrator provisionerOrchestrator,
			io.aipaas.cluster.provisioning.internal.ProvisioningResultMapper provisioningResultMapper,
			io.aipaas.cluster.provisioning.internal.EngineEventAdapter engineEventAdapter) {
		return new AutomationProvisioningService(
				config, provisionerOrchestrator, provisioningResultMapper, engineEventAdapter);
	}
}
