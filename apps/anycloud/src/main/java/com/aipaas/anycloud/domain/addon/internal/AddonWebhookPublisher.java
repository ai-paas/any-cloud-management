package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.properties.AddonWebhookProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Addon state-change webhook publisher.
 *
 * <p>URL 미설정 환경에서는 lookup 전에 short-circuit — 외부 호출 0. RestClient 는 boot 시 1회 생성.
 *
 * <p>실패는 swallow + warn — webhook 실패가 install path 자체를 affect 하지 않도록.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AddonWebhookProperties.class)
public class AddonWebhookPublisher {

    private final AddonWebhookProperties properties;
    private final RestClient restClient;

    public AddonWebhookPublisher(AddonWebhookProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /**
     * State 전이 시점 호출. SUCCEEDED/FAILED 외 상태는 무시 (config 별).
     *
     * <p>property URL 비어있으면 즉시 return — 미설정 환경 비용 0.
     */
    public void notifyStateChange(ClusterAddonEntity addon, AddonState newState) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            return; // not configured.
        }
        boolean shouldFire = (newState == AddonState.SUCCEEDED && properties.isOnSucceeded())
                || (newState == AddonState.FAILED && properties.isOnFailed());
        if (!shouldFire) {
            return;
        }
        try {
            Map<String, Object> body = buildPayload(addon, newState);
            restClient
                    .post()
                    .uri(properties.getUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info(
                    "AddonWebhookPublisher: notified cluster={} addon={} state={}",
                    addon.getClusterId(),
                    addon.getId(),
                    newState);
        } catch (Exception e) {
            // non-fatal — install path 자체엔 영향 없음.
            log.warn(
                    "AddonWebhookPublisher: webhook call failed (non-fatal) cluster={} addon={}: {}",
                    addon.getClusterId(),
                    addon.getId(),
                    e.toString());
        }
    }

    private static Map<String, Object> buildPayload(ClusterAddonEntity addon, AddonState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "addon.state.changed");
        body.put("cluster", addon.getClusterId());
        body.put("addonId", addon.getId());
        body.put("addonType", addon.getAddonType().name());
        body.put("state", state.name());
        body.put("release", addon.getReleaseName());
        body.put("namespace", addon.getNamespace());
        body.put("chartName", addon.getChartName());
        body.put("chartVersion", addon.getChartVersion());
        body.put("attempts", addon.getAttempts());
        body.put("lastError", addon.getLastError());
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
