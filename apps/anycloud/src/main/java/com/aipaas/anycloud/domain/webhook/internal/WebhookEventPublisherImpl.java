package com.aipaas.anycloud.domain.webhook.internal;

import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.cluster.WebhookProperties;
import com.aipaas.anycloud.domain.webhook.WebhookEvent;
import com.aipaas.anycloud.domain.webhook.WebhookEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventPublisherImpl implements WebhookEventPublisher {

    private static final String HEADER_EVENT = "X-Anycloud-Event";
    private static final String HEADER_EVENT_ID = "X-Anycloud-Event-Id";
    private static final String HEADER_TIMESTAMP = "X-Anycloud-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Anycloud-Signature";
    private static final String HMAC_ALGO = "HmacSHA256";

    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Async(AsyncConfig.KUBERNETES_EXECUTOR)
    public void publish(WebhookEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (event == null || event.type() == null) {
            return;
        }
        if (!properties.getEvents().isEmpty() && !properties.getEvents().contains(event.type())) {
            // filter 미일치 — 조용히 스킵.
            return;
        }
        if (properties.getUrls() == null || properties.getUrls().isEmpty()) {
            log.debug("Webhook publish skipped: no URLs configured. event={}", event.type());
            return;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Webhook serialize failed: event={}, error={}", event.type(), e.toString());
            recordMetric(event.type(), "unknown", "serialize_error");
            return;
        }

        String signature = sign(body);
        for (String url : properties.getUrls()) {
            deliverWithRetry(url, event, body, signature);
        }
    }

    private void deliverWithRetry(String url, WebhookEvent event, String body, String signature) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        long backoff = Math.max(0, properties.getInitialIntervalMs());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header(HEADER_EVENT, event.type())
                    .header(HEADER_EVENT_ID, event.id())
                    .header(HEADER_TIMESTAMP, event.timestamp())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (signature != null) {
                reqBuilder.header(HEADER_SIGNATURE, "sha256=" + signature);
            }
            try {
                HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info(
                            "Webhook delivered: type={}, url={}, status={}, attempt={}",
                            event.type(),
                            url,
                            resp.statusCode(),
                            attempt);
                    recordMetric(event.type(), url, "success");
                    return;
                }
                log.warn(
                        "Webhook non-2xx: type={}, url={}, status={}, body={}, attempt={}",
                        event.type(),
                        url,
                        resp.statusCode(),
                        truncate(resp.body(), 200),
                        attempt);
                recordMetric(event.type(), url, "http_" + resp.statusCode());
            } catch (Exception e) {
                log.warn(
                        "Webhook attempt {} failed: type={}, url={}, error={}",
                        attempt,
                        event.type(),
                        url,
                        e.toString());
                recordMetric(event.type(), url, "error");
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff *= 2; // 지수 backoff
            }
        }
        log.error("Webhook giving up after {} attempts: type={}, url={}", attempts, event.type(), url);
        recordMetric(event.type(), url, "giveup");
    }

    private String sign(String body) {
        String secret = properties.getSigningSecret();
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            log.error("Webhook signing failed: {}", e.toString());
            return null;
        }
    }

    private void recordMetric(String eventType, String url, String result) {
        Counter.builder("anycloud.webhook.delivery")
                .tags(Tags.of("event", eventType, "url", url, "result", result))
                .register(meterRegistry)
                .increment();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
