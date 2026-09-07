package io.aipaas.cluster.provisioning.autoconfigure;

import io.aipaas.cluster.provisioning.api.ExecutionConfig;
import io.aipaas.cluster.provisioning.internal.AutomationProvisioningService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** cluster-provisioning starter auto-config. */
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

    /**
     * {@link io.aipaas.cluster.provisioning.api.ProvisioningService} default impl —
     * Pulumi Automation Java SDK 기반 in-JVM 구현.
     */
    @Bean
    @ConditionalOnMissingBean
    public io.aipaas.cluster.provisioning.api.ProvisioningService provisioningService(
            ExecutionConfig config,
            io.aipaas.cluster.provisioning.internal.ProvisioningResultMapper provisioningResultMapper,
            io.aipaas.cluster.provisioning.internal.EngineEventAdapter engineEventAdapter) {
        return new AutomationProvisioningService(config, provisioningResultMapper, engineEventAdapter);
    }
}
