package com.aipaas.anycloud.domain.provisioning.internal;

import com.aipaas.anycloud.common.error.exception.provisioning.ProvisioningException;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterWorkflowProperties;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * VM cluster provisioning RabbitMQ 토폴로지 + retry / DLQ 설정.
 *
 * <p>리스너 컨테이너 팩토리는 {@link RetryInterceptorBuilder#stateless()} 로 stateless retry
 * advice chain 을 단다. 처리 중 예외 → 지수 backoff 로 maxAttempts 만큼 재시도 → 초과 시
 * {@link RejectAndDontRequeueRecoverer} 가 nack(requeue=false) 처리 → broker 가 queue 의
 * x-dead-letter-exchange / x-dead-letter-routing-key 인자에 따라 DLQ 로 라우팅.
 *
 * <p>{@code defaultRequeueRejected=false} 로 두기 때문에 retry interceptor 가 없는 경로 (예:
 * 빈 advice chain) 에서도 메시지가 영구 redeliver loop 에 빠지지 않는다. 모든 큐의
 * x-dead-letter-* 인자는 {@link #workflowQueue} 에서 일괄 부착.
 */
@Configuration
@ConditionalOnProperty(prefix = "vm-cluster-workflow", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(VmClusterWorkflowProperties.class)
public class RabbitMqVmClusterWorkflowConfiguration {

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            VmClusterWorkflowProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // Reject → requeue=false. retry interceptor 가 maxAttempts 초과 후 nack 할 때 DLX 로 라우팅됨.
        // true 로 두면 동일 메시지가 head 로 무한 재진입.
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate(properties))
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    /**
     * ProvisioningException 의 isTransient() 가 false 면 재시도 무의미 — 입력 / 자격증명 / 상태가
     * 영구 실패라 동일 메시지를 다시 처리해도 동일 결과. ExceptionClassifierRetryPolicy 가 클래스 단위로
     * NeverRetryPolicy 를 적용해 즉시 DLQ 로 라우팅하도록 분기.
     *
     * <p>그 외 (TransientProvisioningFailure / PulumiExecutionException / 일반 RuntimeException) 는
     * 기존 SimpleRetryPolicy + ExponentialBackOff 로 maxAttempts 까지 재시도.
     */
    private RetryTemplate retryTemplate(VmClusterWorkflowProperties properties) {
        SimpleRetryPolicy transientPolicy = new SimpleRetryPolicy();
        transientPolicy.setMaxAttempts(properties.getMaxAttempts());

        ExceptionClassifierRetryPolicy classifier = new ExceptionClassifierRetryPolicy();
        classifier.setExceptionClassifier(throwable -> {
            if (throwable instanceof ProvisioningException pe && !pe.isTransient()) {
                return new NeverRetryPolicy();
            }
            return transientPolicy;
        });

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(properties.getInitialIntervalMs());
        backOff.setMultiplier(properties.getMultiplier());
        backOff.setMaxInterval(properties.getMaxIntervalMs());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(classifier);
        template.setBackOffPolicy(backOff);
        return template;
    }

    @Bean
    public DirectExchange vmClusterWorkflowExchange(VmClusterWorkflowProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public DirectExchange vmClusterWorkflowDeadLetterExchange(VmClusterWorkflowProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue vmClusterProvisionQueue(VmClusterWorkflowProperties properties) {
        return workflowQueue(properties.getProvisionQueue(), properties);
    }

    @Bean
    public Queue vmClusterBootstrapQueue(VmClusterWorkflowProperties properties) {
        return workflowQueue(properties.getBootstrapQueue(), properties);
    }

    @Bean
    public Queue vmClusterVerifyQueue(VmClusterWorkflowProperties properties) {
        return workflowQueue(properties.getVerifyQueue(), properties);
    }

    @Bean
    public Queue vmClusterDestroyQueue(VmClusterWorkflowProperties properties) {
        return workflowQueue(properties.getDestroyQueue(), properties);
    }

    @Bean
    public Queue vmClusterDeadLetterQueue(VmClusterWorkflowProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding vmClusterProvisionBinding(
            DirectExchange vmClusterWorkflowExchange,
            Queue vmClusterProvisionQueue,
            VmClusterWorkflowProperties properties) {
        return BindingBuilder.bind(vmClusterProvisionQueue)
                .to(vmClusterWorkflowExchange)
                .with(properties.getProvisionRoutingKey());
    }

    @Bean
    public Binding vmClusterBootstrapBinding(
            DirectExchange vmClusterWorkflowExchange,
            Queue vmClusterBootstrapQueue,
            VmClusterWorkflowProperties properties) {
        return BindingBuilder.bind(vmClusterBootstrapQueue)
                .to(vmClusterWorkflowExchange)
                .with(properties.getBootstrapRoutingKey());
    }

    @Bean
    public Binding vmClusterVerifyBinding(
            DirectExchange vmClusterWorkflowExchange,
            Queue vmClusterVerifyQueue,
            VmClusterWorkflowProperties properties) {
        return BindingBuilder.bind(vmClusterVerifyQueue)
                .to(vmClusterWorkflowExchange)
                .with(properties.getVerifyRoutingKey());
    }

    @Bean
    public Binding vmClusterDestroyBinding(
            DirectExchange vmClusterWorkflowExchange,
            Queue vmClusterDestroyQueue,
            VmClusterWorkflowProperties properties) {
        return BindingBuilder.bind(vmClusterDestroyQueue)
                .to(vmClusterWorkflowExchange)
                .with(properties.getDestroyRoutingKey());
    }

    @Bean
    public Binding vmClusterDeadLetterBinding(
            DirectExchange vmClusterWorkflowDeadLetterExchange,
            Queue vmClusterDeadLetterQueue,
            VmClusterWorkflowProperties properties) {
        return BindingBuilder.bind(vmClusterDeadLetterQueue)
                .to(vmClusterWorkflowDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    private Queue workflowQueue(String queueName, VmClusterWorkflowProperties properties) {
        return QueueBuilder.durable(queueName)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", properties.getDeadLetterExchange(),
                        "x-dead-letter-routing-key", properties.getDeadLetterRoutingKey()))
                .build();
    }
}
