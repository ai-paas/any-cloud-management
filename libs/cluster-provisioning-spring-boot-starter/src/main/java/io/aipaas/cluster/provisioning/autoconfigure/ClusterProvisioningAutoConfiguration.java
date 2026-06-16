package io.aipaas.cluster.provisioning.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.core.ClusterDescriptorRepository;
import io.aipaas.cluster.provisioning.core.ProcessExecutor;
import io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider;
import io.aipaas.cluster.provisioning.core.PulumiExecutionConfig;
import io.aipaas.cluster.provisioning.service.DefaultProcessExecutor;
import io.aipaas.cluster.provisioning.service.ProvisionEventBus;
import io.aipaas.cluster.provisioning.service.PulumiCommandService;
import io.aipaas.cluster.provisioning.service.PulumiCommandServiceImpl;
import io.aipaas.cluster.provisioning.service.PulumiStateBackupScheduler;
import io.aipaas.cluster.provisioning.service.PulumiStateBackupValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * cluster-provisioning starter auto-config.
 *
 * <p>Pulumi 오케스트레이션 service + SPI 포트 default bean 을 명시 등록. host 가 자체 bean 을 등록하면
 * {@code @ConditionalOnMissingBean} 으로 자동 override.
 *
 * <p>kill-switch: {@code cluster-provisioning.enabled=false} → autoConfig 전체 비활성.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ProvisioningProperties.class)
@ConditionalOnProperty(prefix = "cluster-provisioning", name = "enabled", matchIfMissing = true)
public class ClusterProvisioningAutoConfiguration {

	/**
	 * ProvisionEventBus + ProvisioningOutputMapper 가 @Component 대신 평범한 POJO. autoconfig 가
	 * 명시 등록. host 의 @ConditionalOnMissingBean 으로 override 가능.
	 * cluster-agent-spring-boot-starter 와 동일 패턴 (일관성).
	 */
	@org.springframework.context.annotation.Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.service.ProvisionEventBus provisionEventBus() {
		return new io.aipaas.cluster.provisioning.service.ProvisionEventBus();
	}

	@org.springframework.context.annotation.Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.service.ProvisioningOutputMapper provisioningOutputMapper(
			jakarta.validation.Validator validator) {
		return new io.aipaas.cluster.provisioning.service.ProvisioningOutputMapper(validator);
	}

	/**
	 * Backup properties default. host 가 자체 impl 등록 안 하면 backup 기능 비활성 (isEnabled=false).
	 * production 환경에서는 host 가 application.yml 의 자체 prefix 와 결합한 impl 제공 권장.
	 */
	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider
			defaultPulumiBackupPropertiesProvider() {
		log.info("ClusterProvisioningAutoConfiguration: Default PulumiBackupPropertiesProvider — backup 비활성. "
				+ "host 가 자체 bean 등록하면 자동 override.");
		return new io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider
				.DefaultPulumiBackupPropertiesProvider();
	}

	// 참고: ClusterDescriptorRepository 는 default 없음 — host 가 무조건 자체 impl 등록 필요.
	// PulumiStateBackupScheduler 가 이 repository 를 require 하게 되면
	// @ConditionalOnBean(ClusterDescriptorRepository.class) 로 보호.

	/**
	 * 프로세스 실행 포트 default — {@link DefaultProcessExecutor} (ProcessBuilder 기반, shutdown 추적 없음).
	 * host 가 자체 {@link ProcessExecutor} bean 을 등록하면 override (예: anycloud 의 graceful-shutdown
	 * 실행기 — Helm/SSH 와 공유하는 in-flight 추적 + SIGTERM 전파).
	 */
	@Bean
	@ConditionalOnMissingBean
	public ProcessExecutor defaultProcessExecutor() {
		log.info("ClusterProvisioningAutoConfiguration: Default ProcessExecutor (ProcessBuilder, no graceful shutdown). "
				+ "host 가 자체 ProcessExecutor @Bean 을 등록하면 자동 override.");
		return new DefaultProcessExecutor();
	}

	/**
	 * Pulumi 실행 config 포트 default — {@link ProvisioningProperties} 의 {@code pulumi.*} 값 매핑.
	 * host 가 자체 {@link PulumiExecutionConfig} bean 을 등록하면 override (예: anycloud 의 PulumiProperties
	 * — prefix {@code pulumi} — 가 직접 implements 하므로 config 키 변경 없이 그대로 사용).
	 */
	@Bean
	@ConditionalOnMissingBean
	public PulumiExecutionConfig defaultPulumiExecutionConfig(ProvisioningProperties properties) {
		ProvisioningProperties.Pulumi pulumi = properties.pulumi();
		return new PulumiExecutionConfig() {
			@Override
			public String getBinaryPath() {
				return pulumi.binaryPath() != null ? pulumi.binaryPath() : "pulumi";
			}

			@Override
			public Path resolveProjectDir() {
				String dir = pulumi.projectDir() != null ? pulumi.projectDir() : "";
				return Paths.get(dir).toAbsolutePath().normalize();
			}

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
			public boolean isAutoCreateStack() {
				return true;
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

	/**
	 * {@link PulumiCommandService} default impl — Pulumi CLI 오케스트레이션. host 가 자체 구현을 등록하지
	 * 않으면 starter 가 {@link PulumiCommandServiceImpl} 을 제공한다 ({@link ProcessExecutor} +
	 * {@link PulumiExecutionConfig} 포트 위임).
	 */
	@Bean
	@ConditionalOnMissingBean
	public PulumiCommandService pulumiCommandService(PulumiExecutionConfig config,
			ObjectMapper objectMapper, ProcessExecutor processExecutor, ProvisionEventBus eventBus) {
		return new PulumiCommandServiceImpl(config, objectMapper, processExecutor, eventBus);
	}

	/**
	 * Stale lock 자동 복구 guard — {@code pulumi cancel} 후 1 회 재시도. host 가 override 안 하면 제공.
	 */
	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.service.PulumiStaleLockGuard pulumiStaleLockGuard(
			PulumiCommandService pulumiCommandService) {
		return new io.aipaas.cluster.provisioning.service.PulumiStaleLockGuard(pulumiCommandService);
	}

	/**
	 * {@link io.aipaas.cluster.provisioning.service.PulumiProvisioningService} default impl —
	 * stack 준비 + config 적용 + up/preview/destroy 오케스트레이션. host 가 override 안 하면 제공.
	 */
	@Bean
	@ConditionalOnMissingBean
	public io.aipaas.cluster.provisioning.service.PulumiProvisioningService pulumiProvisioningService(
			PulumiExecutionConfig config,
			PulumiCommandService pulumiCommandService,
			io.aipaas.cluster.provisioning.service.ProvisioningOutputMapper provisioningOutputMapper,
			io.aipaas.cluster.provisioning.service.PulumiStaleLockGuard staleLockGuard) {
		return new io.aipaas.cluster.provisioning.service.PulumiProvisioningServiceImpl(
				config, pulumiCommandService, provisioningOutputMapper, staleLockGuard);
	}

	/**
	 * State backup scheduler — host 가 {@link ClusterDescriptorRepository} 를 등록해야 활성
	 * ({@code @ConditionalOnBean}). cron 은 {@code cluster-provisioning.state-backup.cron} (기본 매일
	 * 03:00), 실제 enable 은 {@link PulumiBackupPropertiesProvider#isEnabled()} 로 결정.
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(ClusterDescriptorRepository.class)
	public PulumiStateBackupScheduler pulumiStateBackupScheduler(
			PulumiBackupPropertiesProvider properties,
			PulumiCommandService pulumiCommandService,
			ClusterDescriptorRepository clusterDescriptorRepository,
			MeterRegistry meterRegistry) {
		return new PulumiStateBackupScheduler(properties, pulumiCommandService, clusterDescriptorRepository,
				meterRegistry);
	}

	/**
	 * State backup restore dry-run validator — host 가 {@link ClusterDescriptorRepository} 를 등록해야
	 * 활성. cron 은 {@code cluster-provisioning.state-backup.restore-dry-run.cron} (기본 매일 03:30),
	 * 실제 enable 은 {@link PulumiBackupPropertiesProvider#isRestoreDryRunEnabled()} 로 결정.
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(ClusterDescriptorRepository.class)
	public PulumiStateBackupValidator pulumiStateBackupValidator(
			PulumiBackupPropertiesProvider properties,
			PulumiCommandService pulumiCommandService,
			ClusterDescriptorRepository clusterDescriptorRepository,
			MeterRegistry meterRegistry) {
		return new PulumiStateBackupValidator(properties, pulumiCommandService, clusterDescriptorRepository,
				meterRegistry);
	}
}
