package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.properties.AddonWorkflowProperties;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ topology for cluster addon install/uninstall workflow. */
@Configuration
@ConditionalOnProperty(prefix = "addon-workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AddonWorkflowProperties.class)
public class RabbitMqAddonWorkflowConfiguration {

    @Bean
    public DirectExchange addonWorkflowExchange(AddonWorkflowProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public DirectExchange addonWorkflowDeadLetterExchange(AddonWorkflowProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue addonInstallQueue(AddonWorkflowProperties properties) {
        return workflowQueue(properties.getInstallQueue(), properties);
    }

    @Bean
    public Queue addonUninstallQueue(AddonWorkflowProperties properties) {
        return workflowQueue(properties.getUninstallQueue(), properties);
    }

    @Bean
    public Queue addonDeadLetterQueue(AddonWorkflowProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding addonInstallBinding(
            DirectExchange addonWorkflowExchange, Queue addonInstallQueue, AddonWorkflowProperties properties) {
        return BindingBuilder.bind(addonInstallQueue).to(addonWorkflowExchange).with(properties.getInstallRoutingKey());
    }

    @Bean
    public Binding addonUninstallBinding(
            DirectExchange addonWorkflowExchange, Queue addonUninstallQueue, AddonWorkflowProperties properties) {
        return BindingBuilder.bind(addonUninstallQueue)
                .to(addonWorkflowExchange)
                .with(properties.getUninstallRoutingKey());
    }

    @Bean
    public Binding addonDeadLetterBinding(
            DirectExchange addonWorkflowDeadLetterExchange,
            Queue addonDeadLetterQueue,
            AddonWorkflowProperties properties) {
        return BindingBuilder.bind(addonDeadLetterQueue)
                .to(addonWorkflowDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    /** DLX/DLQ-bound durable queue — VmCluster workflow 와 동일 패턴. */
    private Queue workflowQueue(String queueName, AddonWorkflowProperties properties) {
        return QueueBuilder.durable(queueName)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", properties.getDeadLetterExchange(),
                        "x-dead-letter-routing-key", properties.getDeadLetterRoutingKey()))
                .build();
    }
}
