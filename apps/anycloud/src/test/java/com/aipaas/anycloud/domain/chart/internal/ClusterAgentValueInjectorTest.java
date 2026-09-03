package com.aipaas.anycloud.domain.chart.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.chart.internal.ClusterAgentValueInjector.ImageRef;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * cluster-agent chart 의 backend.grpcAddr + image.repository/tag 자동 주입 회귀 방지.
 * 다른 chart 는 입력을 그대로 통과시켜야 한다는 invariant 도 같이 검증.
 */
class ClusterAgentValueInjectorTest extends AbstractUnitTest {

    private static final String PUBLIC_ENDPOINT = "api.example.com:9090";
    private static final String IMAGE_REF = "consine2c/cluster-agent:dev";

    // AgentProperties record 로 통합. helper 가 builder-like API 제공.
    private static com.aipaas.anycloud.domain.agent.AgentProperties props(
            String publicEndpoint,
            String imageRef,
            boolean tlsEnabled,
            String caCert,
            String serverName,
            boolean insecureSkipVerify) {
        var tls = new com.aipaas.anycloud.domain.agent.AgentProperties.Tls(
                tlsEnabled, caCert, serverName, insecureSkipVerify);
        var grpc = new com.aipaas.anycloud.domain.agent.AgentProperties.Grpc(null, publicEndpoint, tls);
        var manifest = new com.aipaas.anycloud.domain.agent.AgentProperties.Manifest(imageRef, null, null, null, null);
        return new com.aipaas.anycloud.domain.agent.AgentProperties(grpc, manifest, null, null, null);
    }

    private final ClusterAgentValueInjector injector =
            new ClusterAgentValueInjector(props(PUBLIC_ENDPOINT, IMAGE_REF, false, "", "", false));

    /* ---------- chart routing ---------- */

    @Test
    void otherChart_passThrough() {
        String raw = "replicaCount: 3\n";
        assertThat(injector.enrich("ingress-nginx", raw)).isEqualTo(raw);
        assertThat(injector.enrich("ingress-nginx", null)).isNull();
        assertThat(injector.enrich("ingress-nginx", "")).isEmpty();
    }

    @Test
    void caseInsensitiveChartName() {
        String result = injector.enrich("Cluster-Agent", null);

        assertThat(backendGrpcAddr(parse(result))).isEqualTo(PUBLIC_ENDPOINT);
    }

    /* ---------- backend.tls ---------- */

    @Test
    void clusterAgent_tlsDisabledDefault_noInjection() {
        // backend TLS 비활성 (default) 면 본 injector 는 backend.tls 손 안 댐 — chart default
        // (backend.tls.enabled=false) 가 그대로 사용됨. 명시 false 도 emit 하지 않음.
        String result = injector.enrich("cluster-agent", null);

        Map<String, Object> parsed = parse(result);
        // backend.tls 트리 자체가 없거나 사용자가 명시한 그대로.
        assertThat(tlsMap(parsed)).isNull();
    }

    @Test
    void clusterAgent_tlsEnabledViaConfig_injectsAllTlsFields() {
        ClusterAgentValueInjector tlsInjector = new ClusterAgentValueInjector(props(
                PUBLIC_ENDPOINT,
                IMAGE_REF,
                true,
                "-----BEGIN CERTIFICATE-----\nFAKE\n-----END CERTIFICATE-----\n",
                "backend.example.com",
                false));

        String result = tlsInjector.enrich("cluster-agent", null);

        Map<String, Object> parsed = parse(result);
        assertThat(tlsEnabled(parsed)).isTrue();
        assertThat(tlsCaCert(parsed)).contains("FAKE");
        assertThat(tlsServerName(parsed)).isEqualTo("backend.example.com");
    }

    @Test
    void clusterAgent_userProvidedTls_preserved() {
        // 사용자가 backend.tls.enabled=true + caCert 명시 → injector 의 default 가 false 여도 사용자 값 보존.
        String raw = """
				backend:
				  tls:
				    enabled: true
				    caCert: "USER_PROVIDED_CERT"
				""";

        String result = injector.enrich("cluster-agent", raw);

        Map<String, Object> parsed = parse(result);
        assertThat(tlsEnabled(parsed)).isTrue();
        assertThat(tlsCaCert(parsed)).isEqualTo("USER_PROVIDED_CERT");
    }

    /* ---------- backend.grpcAddr ---------- */

    @Test
    void clusterAgent_emptyValues_injectsBackendGrpcAddr() {
        String result = injector.enrich("cluster-agent", null);

        Map<String, Object> parsed = parse(result);
        assertThat(backendGrpcAddr(parsed)).isEqualTo(PUBLIC_ENDPOINT);
    }

    @Test
    void clusterAgent_userProvidedGrpcAddr_keepsUserValue() {
        // 사용자가 backend.grpcAddr + image 둘 다 명시 → enrich 가 원본 그대로 반환 (포맷 보존).
        String raw =
                """
				backend:
				  grpcAddr: my-custom:7777
				image:
				  repository: my-repo/agent
				  tag: prod
				""";

        String result = injector.enrich("cluster-agent", raw);

        assertThat(result).isEqualTo(raw);
        assertThat(backendGrpcAddr(parse(result))).isEqualTo("my-custom:7777");
    }

    @Test
    void clusterAgent_existingBackendWithoutGrpcAddr_injectsOnly() {
        // 사용자가 image 만 명시 + backend 노드는 있으나 grpcAddr 누락.
        // → grpcAddr 만 주입, image 는 사용자 값 유지.
        String raw =
                """
				backend:
				  tls:
				    enabled: true
				image:
				  repository: my-repo/agent
				  tag: prod
				replicaCount: 2
				""";

        String result = injector.enrich("cluster-agent", raw);

        Map<String, Object> parsed = parse(result);
        assertThat(backendGrpcAddr(parsed)).isEqualTo(PUBLIC_ENDPOINT);
        assertThat(imageRepo(parsed)).isEqualTo("my-repo/agent");
        assertThat(imageTag(parsed)).isEqualTo("prod");
        assertThat(parsed).containsEntry("replicaCount", 2);
    }

    @Test
    void clusterAgent_blankGrpcAddr_treatedAsMissing() {
        String raw = "backend:\n  grpcAddr: \"\"\n";

        String result = injector.enrich("cluster-agent", raw);

        assertThat(backendGrpcAddr(parse(result))).isEqualTo(PUBLIC_ENDPOINT);
    }

    /* ---------- image.repository / image.tag ---------- */

    @Test
    void clusterAgent_emptyValues_injectsImage() {
        String result = injector.enrich("cluster-agent", null);

        Map<String, Object> parsed = parse(result);
        assertThat(imageRepo(parsed)).isEqualTo("consine2c/cluster-agent");
        assertThat(imageTag(parsed)).isEqualTo("dev");
    }

    @Test
    void clusterAgent_userProvidedRepoOnly_keepsUserDecision() {
        // repository 만 명시 → 사용자 의도 존중, tag 도 주입하지 않음 (chart default 사용).
        String raw = """
				image:
				  repository: my-private-registry/agent
				""";

        String result = injector.enrich("cluster-agent", raw);

        Map<String, Object> parsed = parse(result);
        assertThat(imageRepo(parsed)).isEqualTo("my-private-registry/agent");
        assertThat(imageTag(parsed)).isNull(); // chart default 가 적용됨
        // grpcAddr 는 여전히 주입.
        assertThat(backendGrpcAddr(parsed)).isEqualTo(PUBLIC_ENDPOINT);
    }

    @Test
    void clusterAgent_userProvidedTagOnly_keepsUserDecision() {
        String raw = """
				image:
				  tag: v0.2.0
				""";

        String result = injector.enrich("cluster-agent", raw);

        Map<String, Object> parsed = parse(result);
        assertThat(imageRepo(parsed)).isNull();
        assertThat(imageTag(parsed)).isEqualTo("v0.2.0");
    }

    /* ---------- parseImageRef ---------- */

    @Test
    void parseImageRef_simpleRepoTag() {
        ImageRef r = ClusterAgentValueInjector.parseImageRef("consine2c/cluster-agent:dev");
        assertThat(r.repository()).isEqualTo("consine2c/cluster-agent");
        assertThat(r.tag()).isEqualTo("dev");
    }

    @Test
    void parseImageRef_registryWithPort_safeSplit() {
        // last-colon-after-last-slash 규칙 — 5000 포트가 tag 로 잘리지 않아야 함.
        ImageRef r = ClusterAgentValueInjector.parseImageRef("registry.local:5000/cluster-agent:dev");
        assertThat(r.repository()).isEqualTo("registry.local:5000/cluster-agent");
        assertThat(r.tag()).isEqualTo("dev");
    }

    @Test
    void parseImageRef_noTag_defaultsToLatest() {
        ImageRef r = ClusterAgentValueInjector.parseImageRef("ghcr.io/aipaas/cluster-agent");
        assertThat(r.repository()).isEqualTo("ghcr.io/aipaas/cluster-agent");
        assertThat(r.tag()).isEqualTo("latest");
    }

    @Test
    void parseImageRef_blankOrNull_fallback() {
        ImageRef r1 = ClusterAgentValueInjector.parseImageRef(null);
        ImageRef r2 = ClusterAgentValueInjector.parseImageRef("");
        assertThat(r1.repository()).isEqualTo("aipaas/cluster-agent");
        assertThat(r1.tag()).isEqualTo("dev");
        assertThat(r2.repository()).isEqualTo("aipaas/cluster-agent");
        assertThat(r2.tag()).isEqualTo("dev");
    }

    @Test
    void parseImageRef_singleName_noSlash() {
        ImageRef r = ClusterAgentValueInjector.parseImageRef("nginx:1.25");
        assertThat(r.repository()).isEqualTo("nginx");
        assertThat(r.tag()).isEqualTo("1.25");
    }

    /* ---------- helpers ---------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        Object o = new Yaml().load(yaml);
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static String backendGrpcAddr(Map<String, Object> values) {
        Map<String, Object> backend = (Map<String, Object>) values.get("backend");
        return backend == null ? null : (String) backend.get("grpcAddr");
    }

    @SuppressWarnings("unchecked")
    private static String imageRepo(Map<String, Object> values) {
        Map<String, Object> image = (Map<String, Object>) values.get("image");
        return image == null ? null : (String) image.get("repository");
    }

    @SuppressWarnings("unchecked")
    private static String imageTag(Map<String, Object> values) {
        Map<String, Object> image = (Map<String, Object>) values.get("image");
        return image == null ? null : (String) image.get("tag");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tlsMap(Map<String, Object> values) {
        Map<String, Object> backend = (Map<String, Object>) values.get("backend");
        return backend == null ? null : (Map<String, Object>) backend.get("tls");
    }

    private static Boolean tlsEnabled(Map<String, Object> values) {
        Map<String, Object> tls = tlsMap(values);
        return tls == null ? null : (Boolean) tls.get("enabled");
    }

    private static String tlsCaCert(Map<String, Object> values) {
        Map<String, Object> tls = tlsMap(values);
        return tls == null ? null : (String) tls.get("caCert");
    }

    private static String tlsServerName(Map<String, Object> values) {
        Map<String, Object> tls = tlsMap(values);
        return tls == null ? null : (String) tls.get("serverName");
    }
}
