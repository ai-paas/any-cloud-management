package com.aipaas.anycloud.domain.kube.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * managedFields 제거 회귀 방지. K8s 응답이 단일/List/array root/null 등 다양한 형태로 들어와도
 * 모두 안전하게 처리해야 한다.
 */
class K8sResponseSanitizerTest extends AbstractUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void singleResource_metadataManagedFields_removed() {
        ObjectNode pod = pod("nginx-1");

        JsonNode result = K8sResponseSanitizer.stripManagedFields(pod);

        assertThat(result.get("metadata").has("managedFields")).isFalse();
        // 다른 metadata 필드는 보존.
        assertThat(result.get("metadata").get("name").asText()).isEqualTo("nginx-1");
        assertThat(result.get("metadata").get("namespace").asText()).isEqualTo("default");
        // spec 도 보존.
        assertThat(result.get("spec").get("nodeName").asText()).isEqualTo("node-a");
    }

    @Test
    void listWrapper_itemsManagedFields_removedFromEach() {
        // K8s PodList 형태 — {items: [Pod, Pod, ...]}.
        ObjectNode list = mapper.createObjectNode();
        ArrayNode items = list.putArray("items");
        items.add(pod("nginx-1"));
        items.add(pod("nginx-2"));

        K8sResponseSanitizer.stripManagedFields(list);

        assertThat(list.get("items").get(0).get("metadata").has("managedFields"))
                .isFalse();
        assertThat(list.get("items").get(1).get("metadata").has("managedFields"))
                .isFalse();
    }

    @Test
    void arrayRoot_eachItem_sanitized() {
        // items 만 array 로 반환되는 경로 (getResources, valueToTree(List<HasMetadata>)).
        ArrayNode arr = mapper.createArrayNode();
        arr.add(pod("nginx-1"));
        arr.add(pod("nginx-2"));

        K8sResponseSanitizer.stripManagedFields(arr);

        assertThat(arr.get(0).get("metadata").has("managedFields")).isFalse();
        assertThat(arr.get(1).get("metadata").has("managedFields")).isFalse();
    }

    @Test
    void nullOrMissing_safe() {
        assertThat(K8sResponseSanitizer.stripManagedFields(null)).isNull();
        assertThat(K8sResponseSanitizer.stripManagedFields(mapper.nullNode()).isNull())
                .isTrue();
        assertThat(K8sResponseSanitizer.stripManagedFields(mapper.missingNode()).isMissingNode())
                .isTrue();
    }

    @Test
    void noMetadata_noChange() {
        // 임의 JSON — metadata 노드 없음. nop.
        ObjectNode obj = mapper.createObjectNode();
        obj.put("foo", "bar");

        K8sResponseSanitizer.stripManagedFields(obj);

        assertThat(obj.get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void metadataWithoutManagedFields_noChange() {
        ObjectNode pod = mapper.createObjectNode();
        ObjectNode meta = pod.putObject("metadata");
        meta.put("name", "nginx-1");

        K8sResponseSanitizer.stripManagedFields(pod);

        // 변경 없음. name 보존.
        assertThat(pod.get("metadata").get("name").asText()).isEqualTo("nginx-1");
        assertThat(pod.get("metadata").has("managedFields")).isFalse();
    }

    @Test
    void emptyArray_safe() {
        ArrayNode arr = mapper.createArrayNode();
        JsonNode result = K8sResponseSanitizer.stripManagedFields(arr);
        assertThat(result.size()).isZero();
    }

    @Test
    void inPlaceMutation_returnsSameInstance() {
        ObjectNode pod = pod("nginx-1");
        JsonNode result = K8sResponseSanitizer.stripManagedFields(pod);
        assertThat(result).isSameAs(pod);
    }

    /* ---------- Secret redact ---------- */

    @Test
    void redactSecretValues_singleSecret_dataValuesRedacted() {
        ObjectNode secret = secret("my-secret", java.util.Map.of("password", "c3VwZXJzZWNyZXQ=", "token", "YWJjMTIz"));

        K8sResponseSanitizer.redactSecretValues(secret);

        ObjectNode data = (ObjectNode) secret.get("data");
        // key 는 보존, value 는 <redacted:N> 형태로 교체. N = 원본 base64 길이.
        assertThat(data.get("password").asText()).isEqualTo("<redacted:16>");
        assertThat(data.get("token").asText()).isEqualTo("<redacted:8>");
        // 다른 metadata 는 그대로 — name / kind 보존.
        assertThat(secret.get("kind").asText()).isEqualTo("Secret");
        assertThat(secret.get("metadata").get("name").asText()).isEqualTo("my-secret");
    }

    @Test
    void redactSecretValues_stringDataAlsoRedacted() {
        ObjectNode secret = secret("plain-secret", java.util.Map.of());
        ObjectNode stringData = secret.putObject("stringData");
        stringData.put("hello", "world");

        K8sResponseSanitizer.redactSecretValues(secret);

        assertThat(secret.get("stringData").get("hello").asText()).isEqualTo("<redacted:5>");
    }

    @Test
    void redactSecretValues_nonSecret_untouched() {
        ObjectNode pod = pod("nginx-1");
        // pod 에 data 필드 추가 (인위적) — redact 대상 아님 확인.
        ObjectNode data = pod.putObject("data");
        data.put("config", "important-value");

        K8sResponseSanitizer.redactSecretValues(pod);

        // kind=Pod 이므로 data 의 value 보존.
        assertThat(pod.get("data").get("config").asText()).isEqualTo("important-value");
    }

    @Test
    void redactSecretValues_secretList_itemsRedacted() {
        ObjectNode list = mapper.createObjectNode();
        list.put("kind", "SecretList");
        ArrayNode items = list.putArray("items");
        // SecretList 의 item 은 kind 필드 없을 수 있음 — list wrapper 가 SecretList 이면 무조건 redact.
        items.add(secretNoKind("s1", java.util.Map.of("k", "dmFsMQ==")));
        items.add(secretNoKind("s2", java.util.Map.of("k", "dmFsMg==")));

        K8sResponseSanitizer.redactSecretValues(list);

        ObjectNode i0 = (ObjectNode) items.get(0);
        ObjectNode i1 = (ObjectNode) items.get(1);
        assertThat(i0.get("data").get("k").asText()).isEqualTo("<redacted:8>");
        assertThat(i1.get("data").get("k").asText()).isEqualTo("<redacted:8>");
    }

    @Test
    void redactSecretValues_nullAndMissing_safe() {
        assertThat(K8sResponseSanitizer.redactSecretValues(null)).isNull();
        assertThat(K8sResponseSanitizer.redactSecretValues(mapper.nullNode()).isNull())
                .isTrue();
        // secret 에 data 없는 경우 — nop.
        ObjectNode secret = secret("empty", java.util.Map.of());
        secret.remove("data");
        K8sResponseSanitizer.redactSecretValues(secret);
        assertThat(secret.has("data")).isFalse();
    }

    private ObjectNode secret(String name, java.util.Map<String, String> data) {
        ObjectNode secret = mapper.createObjectNode();
        secret.put("apiVersion", "v1");
        secret.put("kind", "Secret");
        ObjectNode meta = secret.putObject("metadata");
        meta.put("name", name);
        meta.put("namespace", "default");
        ObjectNode dataNode = secret.putObject("data");
        data.forEach(dataNode::put);
        return secret;
    }

    private ObjectNode secretNoKind(String name, java.util.Map<String, String> data) {
        ObjectNode secret = mapper.createObjectNode();
        // kind 필드 의도적으로 미설정 — SecretList items[] 에서 발견되는 형태.
        ObjectNode meta = secret.putObject("metadata");
        meta.put("name", name);
        ObjectNode dataNode = secret.putObject("data");
        data.forEach(dataNode::put);
        return secret;
    }

    /* ---------- helper ---------- */

    private ObjectNode pod(String name) {
        ObjectNode pod = mapper.createObjectNode();
        pod.put("apiVersion", "v1");
        pod.put("kind", "Pod");
        ObjectNode meta = pod.putObject("metadata");
        meta.put("name", name);
        meta.put("namespace", "default");
        // 진짜 K8s 응답의 managedFields 는 ManagedFieldsEntry 의 배열.
        ArrayNode mf = meta.putArray("managedFields");
        ObjectNode entry = mf.addObject();
        entry.put("manager", "kube-controller-manager");
        entry.put("operation", "Update");
        entry.put("apiVersion", "v1");
        ObjectNode spec = pod.putObject("spec");
        spec.put("nodeName", "node-a");
        return pod;
    }
}
