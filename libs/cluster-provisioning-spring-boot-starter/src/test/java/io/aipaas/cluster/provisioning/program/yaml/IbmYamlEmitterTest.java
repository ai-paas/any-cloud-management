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

/** IBM Cloud VPC YAML 프로그램 회귀 보호. */
class IbmYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "ibm");
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.zone", "us-south-1");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(Map<String, String> cfg) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.NodeRefs refs = new IbmYamlEmitter().emit(b, spec(cfg));
        StandardOutputs.apply(b, spec(cfg), refs);
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");
        return (Map<String, Object>) ((Map<String, Object>) resources.get(name)).get("properties");
    }

    @Test
    @SuppressWarnings("unchecked")
    void declaresBridgedPackage() {
        // 사전 컴파일된 플러그인이 없다. 선언이 빠지면 타입 해석이 pulumiverse 를 찾다 404 로 죽는다.
        Map<String, Object> packages = (Map<String, Object>) doc(cfg()).get("packages");

        assertThat(packages).containsKey("ibm");
        assertThat((Map<String, Object>) packages.get("ibm"))
                .containsEntry("source", "terraform-provider")
                .containsEntry("parameters", List.of("ibm-cloud/ibm"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsNetworkSecurityGroupAndNodes() {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources)
                .containsKeys("sshKeyPair", "sshKey", "vpc", "gateway", "subnet", "secgroup", "master", "fip-master");
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesSchemaVerifiedTypeTokens() {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(((Map<String, Object>) resources.get("vpc")).get("type")).isEqualTo("ibm:index/isVpc:IsVpc");
        assertThat(((Map<String, Object>) resources.get("subnet")).get("type"))
                .isEqualTo("ibm:index/isSubnet:IsSubnet");
        assertThat(((Map<String, Object>) resources.get("master")).get("type"))
                .isEqualTo("ibm:index/isInstance:IsInstance");
        assertThat(((Map<String, Object>) resources.get("fip-master")).get("type"))
                .isEqualTo("ibm:index/isFloatingIp:IsFloatingIp");
    }

    @Test
    void subnetNeedsZoneNotRegion() {
        // region 만으로는 서브넷을 만들 수 없다. zone 은 계정마다 활성 목록이 달라 추측하지 않는다.
        assertThat(props("subnet")).containsEntry("zone", "us-south-1");
    }

    @Test
    void publicGatewayIsAttachedToSubnet() {
        // 없으면 cloud-init 이 패키지 저장소에 닿지 못한다.
        assertThat(props("subnet")).containsEntry("publicGateway", "${gateway.id}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void intraVpcRuleOmitsProtocol() {
        // IBM 은 protocol 을 tcp/udp/icmp 로만 받는다. 생략이 all 이라 Calico IPIP 가 통과한다.
        Map<String, Object> intra = props("sg-intra");

        assertThat(intra).containsEntry("remote", "10.98.0.0/16").doesNotContainKeys("tcp", "udp", "icmp");
    }

    @Test
    void floatingIpTargetsTheNic() {
        // IBM 은 인스턴스에 공인 IP 를 직접 붙이지 않는다.
        assertThat(props("fip-master")).containsEntry("target", "${master.primaryNetworkInterface.id}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicIpComesFromFloatingIp() {
        Map<String, Object> outputs = (Map<String, Object>) doc(cfg()).get("outputs");

        assertThat(outputs.get("masterPublicIp")).isEqualTo("${fip-master.address}");
        assertThat(outputs.get("masterPrivateIp")).isEqualTo("${master.primaryNetworkInterface.primaryIpv4Address}");
    }

    @Test
    void zoneIsRequired() {
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.zone");

        assertThatThrownBy(() -> new IbmYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zone");
    }

    @Test
    void vpcCidrDoesNotOverlapServiceCidr() {
        // 겹치면 kube-proxy 가 서비스 트래픽을 노드 대역으로 보낸다.
        assertThat(spec(cfg()).vpcCidr()).isEqualTo("10.98.0.0/16");
        assertThat(spec(cfg()).serviceCidr()).isEqualTo("10.96.0.0/12");
    }
}
