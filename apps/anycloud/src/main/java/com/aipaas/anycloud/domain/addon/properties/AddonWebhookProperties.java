package com.aipaas.anycloud.domain.addon.properties;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Addon state-change webhook 설정.
 *
 * <p>SUCCEEDED / FAILED 전환 시점에 외부 system 으로 HTTP POST. Slack incoming webhook,
 * 사내 notification gateway, 또는 Discord bot 등 호환. URL 미설정 시 publisher 비활성 (no-op).
 *
 * <p>Payload 형식 (Slack-compatible plain JSON):
 * <pre>{@code
 * {
 *   "event": "addon.state.changed",
 *   "cluster": "orb-kubernetes-001",
 *   "addonId": "addon-uuid",
 *   "addonType": "MONITORING",
 *   "state": "SUCCEEDED",
 *   "release": "kube-prometheus-stack",
 *   "namespace": "monitoring",
 *   "attempts": 1,
 *   "lastError": null,
 *   "timestamp": "2026-06-08T12:34:56Z"
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "addon-webhook")
public class AddonWebhookProperties {

    /** Webhook target URL. blank 이면 publisher 비활성. */
    private String url = "";

    /** HTTP timeout. */
    private Duration timeout = Duration.ofSeconds(5);

    /** 어떤 state 에서 trigger 할지 — SUCCEEDED / FAILED 가 default. */
    private boolean onSucceeded = true;

    private boolean onFailed = true;
}
