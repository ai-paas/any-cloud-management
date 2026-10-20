package com.aipaas.anycloud.domain.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * webhook 전송 이벤트 단위. {@code id}, {@code type}, {@code timestamp} 는 CloudEvents 호환
 * 메타이고 {@code data} 와 {@code links} 는 v1 API 응답과 동일 구조.
 *
 * <p>이 JSON 은 외부 계약. 필드 추가는 안전하나 제거나 이름 변경 시 수신 측 동작 실패.
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
