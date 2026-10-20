package io.aipaas.cluster.provisioning.api;

import java.time.Instant;
import java.util.Map;

/**
 * Pulumi 의 {@code --json} engine event 를 공통 모양으로 정리한 것입니다. Pulumi 쪽 schema 가
 * event 종류마다 달라 호출자가 매번 분기하지 않도록 필요한 필드만 뽑았습니다. 원본이 필요하면
 * {@code raw} 를 보세요.
 *
 * <ul>
 *   <li>{@code type}: pulumi event 종류 ("diagnostic", "resOutputs", "summary" 등)</li>
 *   <li>{@code timestamp}: Pulumi 가 발행한 시각. 없으면 파싱한 시각</li>
 *   <li>{@code resourceUrn}: 관련 resource URN. 없으면 null</li>
 *   <li>{@code message}: diagnostic 메시지나 status 요약. 없으면 null</li>
 *   <li>{@code severity}: "info", "warning", "error". diagnosticEvent 에만 있습니다</li>
 *   <li>{@code raw}: 원본 JSON</li>
 * </ul>
 *
 * <p>{@link ProvisionEventBus} 나 SSE 로 흘려보내기만 하고 저장하지 않습니다. 기록이 필요하면
 * 호스트가 받아서 직접 남겨야 합니다.
 *
 * @param operationId 이 event 를 일으킨 operation. null 이면 필터 없이 전체로 봅니다.
 */
public record ProvisionEvent(
        String operationId,
        String type,
        Instant timestamp,
        String resourceUrn,
        String message,
        String severity,
        Map<String, Object> raw) {

    /**
     * Pulumi 의 engine event JSON map 에서 본 record 를 빌드.
     *
     * <pre>{@code
     * 입력 예:
     * {
     *   "sequence": 5,
     *   "timestamp": 1716700000,
     *   "diagnosticEvent": {
     *     "severity": "info",
     *     "message": "Creating aws:ec2/instance:Instance master-1",
     *     "urn": "urn:pulumi:dev::anycloud::aws:ec2/instance:Instance::master-1"
     *   }
     * }
     * }</pre>
     */
    public static ProvisionEvent fromPulumiJson(String operationId, Map<String, Object> json) {
        Instant ts = readTimestamp(json);
        // 첫 *Event 키를 type 으로 사용.
        String detectedType = null;
        Map<String, Object> detail = null;
        for (Map.Entry<String, Object> e : json.entrySet()) {
            if (e.getKey().endsWith("Event") && e.getValue() instanceof Map<?, ?> m) {
                detectedType = e.getKey().replace("Event", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                detail = casted;
                break;
            }
        }
        if (detectedType == null) {
            return new ProvisionEvent(operationId, "unknown", ts, null, null, null, json);
        }
        String urn = strOrNull(detail.get("urn"));
        String msg = strOrNull(detail.get("message"));
        String sev = strOrNull(detail.get("severity"));
        return new ProvisionEvent(operationId, detectedType, ts, urn, msg, sev, json);
    }

    private static Instant readTimestamp(Map<String, Object> json) {
        Object raw = json.get("timestamp");
        if (raw instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return Instant.now();
    }

    private static String strOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
