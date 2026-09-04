package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlRefTest {

    @Test
    void referenceUsesDollarBraceForm() {
        // Pulumi YAML 은 ${resource.property} 로만 리소스를 참조한다.
        assertThat(YamlRef.of("net", "id")).isEqualTo("${net.id}");
    }

    @Test
    void nestedPropertyPathIsPreserved() {
        assertThat(YamlRef.of("instance", "accessIpV4")).isEqualTo("${instance.accessIpV4}");
    }

    @Test
    void interpolateEmbedsReferencesInsideText() {
        // applyValue(ip -> "https://" + ip + ":6443") 의 YAML 등가물.
        String result = YamlRef.interpolate("https://%s:6443", YamlRef.of("master", "publicIp"));

        assertThat(result).isEqualTo("https://${master.publicIp}:6443");
    }

    @Test
    void secretWrapsValueInFnSecret() {
        Map<String, Object> wrapped = YamlRef.secret(YamlRef.of("key", "privateKeyPem"));

        assertThat(wrapped).containsExactly(Map.entry("fn::secret", "${key.privateKeyPem}"));
    }

    @Test
    void secretAcceptsPlainStrings() {
        // sshCommand 처럼 보간 문자열도 secret 이어야 한다 — 키 경로가 노출된다.
        assertThat(YamlRef.secret("ssh -i ./secrets/demo.pem ubuntu@1.2.3.4")).containsKey("fn::secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeCarriesFunctionAndArguments() {
        Map<String, Object> invoke =
                YamlRef.invoke("openstack:images/getImage:getImage", Map.of("name", "ubuntu-24.04"), null);

        Map<String, Object> body = (Map<String, Object>) invoke.get("fn::invoke");
        assertThat(body.get("function")).isEqualTo("openstack:images/getImage:getImage");
        assertThat((Map<String, Object>) body.get("arguments")).containsEntry("name", "ubuntu-24.04");
        assertThat(body).doesNotContainKey("return");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeWithReturnFieldSelectsSingleValue() {
        // return 을 주면 객체 전체가 아니라 한 필드만 받는다.
        Map<String, Object> invoke = YamlRef.invoke("openstack:images/getImage:getImage", Map.of("name", "u"), "id");

        assertThat(((Map<String, Object>) invoke.get("fn::invoke")).get("return"))
                .isEqualTo("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeArgumentsPreserveNestedStructure() {
        Map<String, Object> invoke = YamlRef.invoke(
                "aws:ec2/getAmi:getAmi", Map.of("owners", List.of("099720109477"), "mostRecent", true), "id");

        Map<String, Object> args =
                (Map<String, Object>) ((Map<String, Object>) invoke.get("fn::invoke")).get("arguments");
        assertThat((List<String>) args.get("owners")).containsExactly("099720109477");
        assertThat(args.get("mostRecent")).isEqualTo(true);
    }
}
