package com.aipaas.anycloud.domain.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 외부 webhook 으로 전송되는 단일 이벤트.
 * <p>
 * 구조는 v1 API 응답 envelope 와 일관: {@code data} + {@code links}.
 * {@code id / type / timestamp} 는 CloudEvents 호환 메타 필드 (HTTP 응답의 {@code meta} 에 해당).
 * <p>
 * <b>JSON serialization 은 외부 계약</b>이므로 필드 추가/제거 시 호환성 주의. 필드 추가는 안전,
 * 제거는 deprecation 절차 필요.
 *
 * <pre>{@code
 *   {
 *     "id": "uuid",
 *     "type": "vm-cluster.ready",
 *     "timestamp": "2026-05-11T03:45:21Z",
 *     "data": { "clusterName": "demo-aws-01", "status": "READY", ... },
 *     "links": {
 *       "resource": "/v1/clusters/demo-aws-01",
 *       "events": "/v1/clusters/demo-aws-01/events"
 *     }
 *   }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookEvent(
        String id, String type, String timestamp, Map<String, Object> data, Map<String, String> links) {

    public static WebhookEvent of(String type, Map<String, Object> data) {
        return new WebhookEvent(
                UUID.randomUUID().toString(),
                type,
                Instant.now().toString(),
                data == null ? Map.of() : new LinkedHashMap<>(data),
                null);
    }

    public WebhookEvent withLinks(Map<String, String> linkMap) {
        return new WebhookEvent(id, type, timestamp, data, linkMap);
    }
}
