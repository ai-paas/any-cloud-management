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

/** GCP YAML 프로그램 회귀 보호. */
class GcpYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "gcp");
        cfg.put("name", "demo");
        cfg.put("region", "asia-northeast3");
        cfg.put("workerCount", "2");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.project", "demo-project");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(Map<String, String> cfg) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        new GcpYamlEmitter().emit(b, spec(cfg));
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> res(String name) {
        return (Map<String, Object>) ((Map<String, Object>) doc(cfg()).get("resources")).get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        return (Map<String, Object>) res(name).get("properties");
    }

    @Test
    void emitsNetworkFirewallAndNodes() {
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources)
                .containsKeys(
                        "sshKey",
                        "net",
                        "subnet",
                        "fw-external",
                        "fw-internal",
                        "ip-master",
                        "master",
                        "ip-worker-1",
                        "worker-1",
                        "worker-2");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        assertThat(res("net").get("type")).isEqualTo("gcp:compute/network:Network");
        assertThat(res("subnet").get("type")).isEqualTo("gcp:compute/subnetwork:Subnetwork");
        assertThat(res("fw-external").get("type")).isEqualTo("gcp:compute/firewall:Firewall");
        assertThat(res("master").get("type")).isEqualTo("gcp:compute/instance:Instance");
        assertThat(res("ip-master").get("type")).isEqualTo("gcp:compute/address:Address");
    }

    @Test
    void disablesAutoSubnetsSoVpcCidrHolds() {
        // 자동 서브넷은 전 리전에 대역을 잡아 vpcCidr 과 충돌한다.
        assertThat(props("net")).containsEntry("autoCreateSubnetworks", false);
    }

    @Test
    void cloudInitGoesToUserDataNotStartupScript() {
        // startup-script 는 셸만 받아 kubeadm cloud-init 흐름이 깨진다.
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) props("master").get("metadata");

        assertThat(metadata).containsKey("user-data");
        assertThat(metadata).doesNotContainKey("startup-script");
    }

    @Test
    void internalFirewallAllowsIpipForCalico() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allows =
                (List<Map<String, Object>>) props("fw-internal").get("allows");

        assertThat(allows).anySatisfy(rule -> assertThat(rule).containsEntry("protocol", "ipip"));
    }

    @Test
    void missingProjectFailsBeforePreview() {
        // 필수 값이 비면 preview 까지 가지 말고 즉시 알린다.
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.project");

        assertThatThrownBy(() -> new GcpYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerSpec.project");
    }

    @Test
    void nodesGetStaticExternalAddress() {
        // bootstrap 이 SSH 로 붙어야 하므로 공인 IP 가 필요하다.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nics =
                (List<Map<String, Object>>) props("master").get("networkInterfaces");

        assertThat(nics.get(0).get("accessConfigs").toString()).contains("${ip-master.address}");
    }
}
