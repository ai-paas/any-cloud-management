package com.aipaas.anycloud.domain.addon.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cluster addon workflow RabbitMQ topology + retry config.
 *
 * <p>VmCluster workflow 패턴 모방 — DirectExchange + install/uninstall queue + DLQ +
 * stateless retry interceptor.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "addon-workflow")
public class AddonWorkflowProperties {

    /** Listener 활성화 toggle (worker mode 분리 시 사용). */
    private boolean enabled = true;

    private boolean workerEnabled = true;

    private String exchange = "addon.workflow";
    private String deadLetterExchange = "addon.workflow.dlx";
    private String deadLetterQueue = "addon.workflow.dlq";

    private String installQueue = "addon.install";
    private String uninstallQueue = "addon.uninstall";

    private String installRoutingKey = "addon.install";
    private String uninstallRoutingKey = "addon.uninstall";
    private String deadLetterRoutingKey = "addon.dead-letter";

    /** Listener 의 backoff retry 설정. helm install 은 ~5분 timeout — 그 1번이 retry 1회로 sufficient. */
    private int maxAttempts = 3;

    private long initialIntervalMs = 5000L;
    private double multiplier = 2.0d;
    private long maxIntervalMs = 60_000L;
}
