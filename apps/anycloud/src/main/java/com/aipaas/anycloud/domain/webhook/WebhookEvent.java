package com.aipaas.anycloud.domain.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * webhook 으로 나가는 이벤트 하나입니다. {@code id}, {@code type}, {@code timestamp} 는
 * CloudEvents 호환 메타이고 {@code data} 와 {@code links} 는 v1 API 응답과 같은 모양입니다.
 *
 * <p>이 JSON 은 외부 계약입니다. 필드를 더하는 건 안전하지만 빼거나 이름을 바꾸면 수신 측이
 * 깨집니다.
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
