package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** 플러그인 버전 고정 회귀 보호. */
class PluginVersionsTest {

    @Test
    void resolvesPackageFromTypeToken() {
        PluginVersions v = PluginVersions.parse("aws:7.44.0 azure-native:3.27.0 tls:5.6.0");

        assertThat(v.forType("aws:ec2/instance:Instance")).isEqualTo("7.44.0");
        // 패키지 이름에 하이픈이 들어간다.
        assertThat(v.forType("azure-native:network:Subnet")).isEqualTo("3.27.0");
        assertThat(v.forType("tls:index/privateKey:PrivateKey")).isEqualTo("5.6.0");
    }

    @Test
    void unknownPackageIsNotPinned() {
        PluginVersions v = PluginVersions.parse("aws:7.44.0");

        assertThat(v.forType("gcp:compute/instance:Instance")).isNull();
        assertThat(v.forType(null)).isNull();
    }

    @Test
    void malformedSpecFailsFast() {
        // 조용히 넘어가면 고정이 풀린 채로 배포된다.
        assertThatThrownBy(() -> PluginVersions.parse("aws-7.44.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name:version");
        assertThatThrownBy(() -> PluginVersions.parse("aws:")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultCoversEveryEmitterPackage() {
        // Dockerfile 이 설치하는 목록과 어긋나면 런타임에 플러그인을 다시 받는다.
        Map<String, String> defaults =
                PluginVersions.parse(PluginVersions.DEFAULT).asMap();

        assertThat(defaults).containsKeys("aws", "gcp", "azure-native", "oci", "openstack", "tls");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emittedResourceCarriesVersion() {
        PulumiProgram.Builder b = PulumiProgram.builder("p");
        b.resource("k", "tls:index/privateKey:PrivateKey", Map.of("algorithm", "RSA"));

        Map<String, Object> doc =
                (Map<String, Object>) new Yaml().load(b.build().toYaml());
        Map<String, Object> res = (Map<String, Object>) ((Map<String, Object>) doc.get("resources")).get("k");
        Map<String, Object> options = (Map<String, Object>) res.get("options");

        assertThat(options).containsKey("version");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitOptionsSurvive() {
        PulumiProgram.Builder b = PulumiProgram.builder("p");
        b.resource("k", "tls:index/privateKey:PrivateKey", Map.of("algorithm", "RSA"), Map.of("dependsOn", "${x}"));

        Map<String, Object> doc =
                (Map<String, Object>) new Yaml().load(b.build().toYaml());
        Map<String, Object> res = (Map<String, Object>) ((Map<String, Object>) doc.get("resources")).get("k");
        Map<String, Object> options = (Map<String, Object>) res.get("options");

        assertThat(options).containsEntry("dependsOn", "${x}").containsKey("version");
    }
}
