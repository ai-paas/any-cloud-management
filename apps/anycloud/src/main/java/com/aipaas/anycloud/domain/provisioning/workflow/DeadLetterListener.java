package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ DLQ listener.
 * <p>
 * 본 listener 는:
 * <ol>
 *   <li>DLQ 메시지를 consume 하면서 즉시 ack (재발행은 별도 결정).</li>
 *   <li>구조화 로그 + headers / body 요약 기록.</li>
 *   <li>Micrometer counter {@code anycloud.workflow.dlq.received{originalQueue,reason}}
 *       — Prometheus 알람으로 사용:
 *       {@code increase(anycloud_workflow_dlq_received_total[5m]) > 0} → page.</li>
 * </ol>
 * <p>
 * 비활성 toggle: {@code anycloud.workflow.dlq-listener.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "anycloud.workflow.dlq-listener",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DeadLetterListener {

    private final MeterRegistry meterRegistry;

    /**
     * VmClusterWorkflowProperties.deadLetterQueue 기본값 {@code vm-cluster.workflow.dlq} 를
     * 직접 매핑. property 로 override 됐을 때를 위해 SpEL 표현식.
     */
    @RabbitListener(queues = "${vm-cluster-workflow.dead-letter-queue:vm-cluster.workflow.dlq}")
    public void onDeadLetter(Message message) {
        Map<String, String> mdc = Map.of(LoggingMdc.STEP, "DLQ");
        try (var ignored = LoggingMdc.scope(mdc)) {
            String body = new String(message.getBody());
            var headers = message.getMessageProperties().getHeaders();
            Object originalQueue = headers.get("x-first-death-queue");
            Object reason = headers.get("x-first-death-reason");

            log.error(
                    "DLQ message received: queue={}, reason={}, body={}", originalQueue, reason, truncate(body, 1000));

            Counter.builder("anycloud.workflow.dlq.received")
                    .description("RabbitMQ DLQ 적재 메시지 수신 누적")
                    .tags(Tags.of(
                            "originalQueue", String.valueOf(originalQueue),
                            "reason", String.valueOf(reason)))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            // DLQ listener 자체가 실패하면 무한 루프 위험 → swallow + log only.
            log.warn("DLQ listener failure (message will not requeue): {}", e.toString(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
