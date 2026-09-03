package com.aipaas.anycloud.domain.provisioning.payload.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T1 (#12) — rawOutputs sanitize 의 defense-in-depth 회귀 보호.
 *
 * <p>기존: {@code sshPrivateKeyPem} 단일 키만 REDACT. 변경: 키 이름에 secret 관련 패턴
 * (privateKey/password/secret/token/credential/apiKey/bearerToken) 이 포함되면 자동 REDACT
 * — 미래에 provider 가 새 secret 필드를 추가해도 silent leak 안 함.
 *
 * <p>사용자 응답에 명시적으로 노출되는 키 (예: masterSshCommand) 는 보존되어야 함 —
 * 패턴이 너무 broad 해서 user-facing 필드까지 redact 하지 않도록 회귀 보호.
 */
class VmClusterPayloadServiceSanitizationTest extends AbstractUnitTest {

    private final VmClusterPayloadServiceImpl service = new VmClusterPayloadServiceImpl(new ObjectMapper());

    @Test
    void sanitize_alwaysRedactsSshPrivateKeyPem() {
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("sshPrivateKeyPem", "-----BEGIN RSA PRIVATE KEY-----\nMIIEvQ...\n-----END RSA PRIVATE KEY-----");
        outputs.put("apiServerUrl", "https://1.2.3.4:6443");

        String json = service.serializeSanitizedOutputs(outputs);

        assertThat(json).contains("\"sshPrivateKeyPem\":\"REDACTED\"");
        assertThat(json).contains("\"apiServerUrl\":\"https://1.2.3.4:6443\"");
        assertThat(json).doesNotContain("MIIEvQ");
    }

    @Test
    void sanitize_redactsKeysMatchingSecretPattern() {
        // Future-proof: 미지의 secret 필드 명도 패턴 매칭으로 잡아냄.
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("dbPassword", "supersecret");
        outputs.put("ociApiKey", "abc123");
        outputs.put("oauthBearerToken", "eyJhbGc...");
        outputs.put("foobarSecret", "mySecret");
        outputs.put("clientCredential", "creds");
        outputs.put("apiServerUrl", "https://1.2.3.4:6443");

        String json = service.serializeSanitizedOutputs(outputs);

        assertThat(json).contains("\"dbPassword\":\"REDACTED\"");
        assertThat(json).contains("\"ociApiKey\":\"REDACTED\"");
        assertThat(json).contains("\"oauthBearerToken\":\"REDACTED\"");
        assertThat(json).contains("\"foobarSecret\":\"REDACTED\"");
        assertThat(json).contains("\"clientCredential\":\"REDACTED\"");
        // 정상 필드는 보존.
        assertThat(json).contains("\"apiServerUrl\":\"https://1.2.3.4:6443\"");
    }

    @Test
    void sanitize_preservesUserFacingFields_evenIfNameLooksSimilar() {
        // masterSshCommand 는 user-facing field — 키 자체에 "Key"/"Token"/"Secret" 가 들어가지
        // 않으므로 redact 되지 않아야 함.
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("masterSshCommand", "ssh -i ./secrets/demo.pem ubuntu@1.2.3.4");
        outputs.put("kubeconfigFetchCommand", "scp -i ./secrets/demo.pem ubuntu@1.2.3.4:/etc/kubernetes/admin.conf .");

        String json = service.serializeSanitizedOutputs(outputs);

        assertThat(json).contains("\"masterSshCommand\":\"ssh -i ./secrets/demo.pem ubuntu@1.2.3.4\"");
        assertThat(json).contains("\"kubeconfigFetchCommand\":");
        assertThat(json).doesNotContain("REDACTED");
    }

    @Test
    void sanitize_caseInsensitivePatternMatch() {
        // "privatekey", "PRIVATEKEY", "PrivateKey" 모두 매칭.
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("vendorPRIVATEKEY", "x");
        outputs.put("MyPrivateKey", "y");

        String json = service.serializeSanitizedOutputs(outputs);

        assertThat(json).contains("\"vendorPRIVATEKEY\":\"REDACTED\"");
        assertThat(json).contains("\"MyPrivateKey\":\"REDACTED\"");
    }

    @Test
    void sanitize_emptyOutputs_returnsEmptyJson() {
        String json = service.serializeSanitizedOutputs(new HashMap<>());
        assertThat(json).isEqualTo("{}");
    }
}
