package com.aipaas.anycloud.domain.kube.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * K8s 응답 JSON 에서 노이즈 / 민감 정보 필드 제거. 현재 대상:
 * <ul>
 *   <li>{@code metadata.managedFields} — Server-Side Apply tracking. 일반 자원의 70-90% 를 차지
 *       하는 경우가 흔하고 UI/대시보드에선 거의 안 쓰임. 제거 시 응답 크기 / 파싱 비용 / 메모리 모두
 *       크게 감소. ({@link #stripManagedFields})</li>
 *   <li>{@code Secret.data} / {@code Secret.stringData} — base64 / plain value redaction.
 *       wildcard read RBAC 트레이드오프를 backend-side 에서 완화. 운영자가
 *       {@code security.kube.redact-secrets=false} 로 비활성 가능 (default ON).
 *       ({@link #redactSecretValues})</li>
 * </ul>
 * <p>
 * 동작 원칙:
 * <ul>
 *   <li>in-place mutation. 입력 JsonNode 가 ObjectMapper 가 만든 mutable 트리라는 가정.</li>
 *   <li>recursive — array / object / list-wrapper (items[]) 모두 처리.</li>
 *   <li>null / missing 안전 — 어떤 형태가 와도 silent skip.</li>
 *   <li>kube 응답 외 임의 JSON 에는 무해 (metadata 노드 없으면 nop).</li>
 * </ul>
 * <p>
 * 호출 위치: agent path / fabric8 path 모두 응답 DTO build 직전. 두 path 통합.
 */
public final class K8sResponseSanitizer {

    private K8sResponseSanitizer() {}

    /**
     * managedFields 를 in-place 제거. null 이면 그대로 반환.
     */
    public static JsonNode stripManagedFields(JsonNode node) {
        walk(node);
        return node;
    }

    private static void walk(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                walk(item);
            }
            return;
        }
        if (!node.isObject()) {
            return; // scalar — 흥미 없음
        }
        // object 시작.
        // 1) metadata.managedFields 제거 (단일 자원 형태).
        JsonNode metadata = node.get("metadata");
        if (metadata instanceof ObjectNode metaObj) {
            metaObj.remove("managedFields");
        }
        // 2) items 배열 — K8s List wrapper (PodList/Deployment List 등) 케이스.
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                walk(item);
            }
        }
        // 3) Multi-doc apply 응답 — 단일 객체가 아닌 array 가 루트인 경우는 isArray() 가 위에서
        //    이미 처리. 객체 안의 추가 nested resource 는 K8s 표준 응답엔 없음 — 더 파지 않음.
    }

    /**
     * Secret 자원의 {@code data} (base64) / {@code stringData} (plain) value 를 redact.
     * wildcard read RBAC 트레이드오프 mitigation.
     *
     * <p>kind 가 "Secret" 인 응답에 대해서만 동작 — 다른 자원은 그대로. List wrapper (SecretList)
     * 도 items[] 재귀로 처리. 결과: data 의 모든 value 가 {@code "<redacted:<len>>"} 문자열로 교체,
     * key 와 size 정보는 보존 (UI 가 "이런 key 가 있다" 표시 가능).
     *
     * <p>caller 가 redact-secrets toggle 끈 경우 본 메서드 호출 자체를 skip.
     *
     * @return 입력 node (in-place mutation)
     */
    public static JsonNode redactSecretValues(JsonNode node) {
        walkRedact(node);
        return node;
    }

    private static void walkRedact(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                walkRedact(item);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        // kind=Secret 인지 확인. K8s 응답 형식: {kind: "Secret", data: {key: "base64..."}, stringData: {...}}
        JsonNode kind = node.get("kind");
        boolean isSecret = kind != null && kind.isTextual() && "Secret".equals(kind.asText());
        boolean isSecretList = kind != null && kind.isTextual() && "SecretList".equals(kind.asText());

        if (isSecret) {
            redactDataMaps((ObjectNode) node);
        }
        // SecretList 또는 items wrapper — 각 item 의 kind 가 Secret 이면 자동 redact (재귀).
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            // SecretList 일 때 items 의 각 entry 가 kind 필드 없을 수도 있음 — list wrapper 가 SecretList
            // 이면 items 의 entry 는 모두 Secret 으로 간주.
            for (JsonNode item : items) {
                if (item.isObject()) {
                    if (isSecretList) {
                        redactDataMaps((ObjectNode) item);
                    } else {
                        walkRedact(item);
                    }
                }
            }
        }
    }

    /** data / stringData object 의 모든 value 를 "<redacted:<len>>" 으로 교체. */
    private static void redactDataMaps(ObjectNode secretNode) {
        redactValueMap(secretNode.get("data"));
        redactValueMap(secretNode.get("stringData"));
    }

    private static void redactValueMap(JsonNode dataNode) {
        if (!(dataNode instanceof ObjectNode dataObj)) {
            return;
        }
        java.util.Iterator<String> fieldNames = dataObj.fieldNames();
        java.util.List<String> keys = new java.util.ArrayList<>();
        while (fieldNames.hasNext()) {
            keys.add(fieldNames.next());
        }
        for (String k : keys) {
            JsonNode v = dataObj.get(k);
            int len = v == null || !v.isTextual() ? 0 : v.asText().length();
            dataObj.set(k, TextNode.valueOf("<redacted:" + len + ">"));
        }
    }
}
