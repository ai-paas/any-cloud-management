package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** OCI YAML 프로그램 회귀 보호. */
class OciYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "oci");
        cfg.put("name", "demo");
        cfg.put("region", "ap-seoul-1");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.compartmentId", "ocid1.compartment.oc1..demo");
        cfg.put("osImage", "ocid1.image.oc1.ap-seoul-1.demo");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc() {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        new OciYamlEmitter().emit(b, spec(cfg()));
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> res(String name) {
        return (Map<String, Object>) ((Map<String, Object>) doc().get("resources")).get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        return (Map<String, Object>) res(name).get("properties");
    }

    @Test
    void emitsNetworkAndNodes() {
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc().get("resources");

        assertThat(resources)
                .containsKeys("sshKey", "vcn", "igw", "routeTable", "securityList", "subnet", "master", "worker-1");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        assertThat(res("vcn").get("type")).isEqualTo("oci:Core/vcn:Vcn");
        assertThat(res("subnet").get("type")).isEqualTo("oci:Core/subnet:Subnet");
        assertThat(res("igw").get("type")).isEqualTo("oci:Core/internetGateway:InternetGateway");
        assertThat(res("routeTable").get("type")).isEqualTo("oci:Core/defaultRouteTable:DefaultRouteTable");
        assertThat(res("securityList").get("type")).isEqualTo("oci:Core/defaultSecurityList:DefaultSecurityList");
        assertThat(res("master").get("type")).isEqualTo("oci:Core/instance:Instance");
    }

    @Test
    @SuppressWarnings("unchecked")
    void userDataIsBase64Encoded() {
        // OCI 는 평문 user-data 를 그대로 무시한다. cloud-init 이 아예 돌지 않는다.
        Map<String, Object> metadata = (Map<String, Object>) props("master").get("metadata");

        assertThat((Map<String, Object>) metadata.get("user_data")).containsKey("fn::toBase64");
    }

    @Test
    void overwritesVcnDefaultRouteTableAndSecurityList() {
        // 새로 만들면 서브넷이 기본 것을 물고 있어 규칙이 적용되지 않는다.
        assertThat(props("routeTable")).containsEntry("manageDefaultResourceId", "${vcn.defaultRouteTableId}");
        assertThat(props("securityList")).containsEntry("manageDefaultResourceId", "${vcn.defaultSecurityListId}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void securityListUsesProtocolNumbersIncludingIpip() {
        // OCI security list 는 프로토콜 이름이 아니라 IANA 번호를 받는다.
        List<Map<String, Object>> ingress =
                (List<Map<String, Object>>) props("securityList").get("ingressSecurityRules");

        assertThat(ingress).anySatisfy(rule -> assertThat(rule).containsEntry("protocol", "6"));
        assertThat(ingress).anySatisfy(rule -> assertThat(rule).containsEntry("protocol", "4"));
    }

    @Test
    void dnsLabelIsSanitizedAndCapped() {
        Map<String, String> cfg = cfg();
        cfg.put("name", "demo-cluster-with-a-very-long-name");

        PulumiProgram.Builder b = PulumiProgram.builder("x");
        new OciYamlEmitter().emit(b, spec(cfg));
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
                (Map<String, Object>) new Yaml().load(b.build().toYaml());
        @SuppressWarnings("unchecked")
        Map<String, Object> vcn = (Map<String, Object>) ((Map<String, Object>) parsed.get("resources")).get("vcn");
        @SuppressWarnings("unchecked")
        String label = (String) ((Map<String, Object>) vcn.get("properties")).get("dnsLabel");

        assertThat(label).hasSizeLessThanOrEqualTo(15).matches("[a-z0-9]+");
    }

    @Test
    void missingImageFailsBeforePreview() {
        // OCI 이미지 OCID 는 리전마다 다르다. 추측하면 엉뚱한 이미지가 뜬다.
        Map<String, String> cfg = cfg();
        cfg.remove("osImage");

        assertThatThrownBy(() -> new OciYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("osImage");
    }

    @Test
    void missingCompartmentFailsBeforePreview() {
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.compartmentId");

        assertThatThrownBy(() -> new OciYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compartmentId");
    }
}
