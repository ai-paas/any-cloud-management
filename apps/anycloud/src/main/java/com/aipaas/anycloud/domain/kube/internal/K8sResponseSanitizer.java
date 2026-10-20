package com.aipaas.anycloud.domain.kube.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * K8s 응답에서 {@code metadata.managedFields} 제거, Secret 값 마스킹.
 *
 * <p>managedFields 는 Server-Side Apply 추적용이며 자원 하나의 70~90% 를 차지하는 경우가 많고
 * 화면에서 미사용.
 *
 * <p>Secret 의 {@code data} 와 {@code stringData} 는 기본 마스킹. agent 의 read 권한이
 * wildcard 라 값이 그대로 응답에 포함. {@code security.kube.redact-secrets=false} 로 비활성화 가능.
 *
 * <p>입력 트리를 in-place 수정. ObjectMapper 가 생성한 mutable 트리 전달 필요.
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
        // 2) items 배열: K8s List wrapper (PodList/Deployment List 등) 케이스.
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                walk(item);
            }
        }
        // 3) Multi-doc apply 응답: 단일 객체가 아닌 array 가 루트인 경우는 isArray() 가 위에서
        //    이미 처리. 객체 안의 추가 nested resource 는 K8s 표준 응답엔 없음. 더 파지 않음.
    }

    /**
     * Secret 자원의 {@code data} (base64) / {@code stringData} (plain) value 를 redact.
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
        // SecretList 또는 items wrapper: 각 item 의 kind 가 Secret 이면 자동 redact (재귀).
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            // SecretList 일 때 items 의 각 entry 가 kind 필드 없을 수도 있음. list wrapper 가 SecretList
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
