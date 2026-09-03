package io.aipaas.cluster.provisioning.api;

import java.time.Instant;
import java.util.Map;

/**
 * Pulumi {@code --json} engine event 의 정규화 표현.
 *
 * <p>Pulumi 의 raw event 는 type 별로 schema 가 다양함 — preludeEvent, diagnosticEvent,
 * resourcePreEvent, resOutputsEvent, resourceFailedEvent, summaryEvent. 본 record 는 caller 가
 * 필요한 부분만 쉽게 꺼낼 수 있도록 공통 필드만 발췌:
 *
 * <ul>
 *   <li>{@code type} — pulumi event kind (e.g. "diagnostic", "resOutputs", "summary").</li>
 *   <li>{@code timestamp} — Pulumi 가 발행한 시각 (없으면 parse 시각).</li>
 *   <li>{@code resourceUrn} — 관련 resource URN (없으면 null).</li>
 *   <li>{@code message} — diagnostic 메시지 또는 status 요약 (없으면 null).</li>
 *   <li>{@code severity} — "info" / "warning" / "error" (diagnosticEvent 만).</li>
 *   <li>{@code raw} — 원본 JSON map (frontend / debug 가 필요하면).</li>
 * </ul>
 *
 * <p>본 event 는 in-process push channel ({@link ProvisionEventBus}) 또는 SSE 로 fan-out.
 * 별도 DB 저장 없음 — 운영 audit 은 host 측의 별도 history table 이 담당.
 *
 * @param operationId 트리거한 operation 식별 (event filtering 용). null 이면 전체 event 로 treat.
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
