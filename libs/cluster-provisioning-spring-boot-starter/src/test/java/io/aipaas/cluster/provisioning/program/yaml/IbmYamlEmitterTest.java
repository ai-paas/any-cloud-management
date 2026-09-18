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

/**
 * IBM Cloud VPC YAML 프로그램 회귀 보호.
 *
 * <p>이미지 이름에는 빌드 번호가 붙고 주기적으로 갈린다. 기본값을 두지 않고 받는 이유는
 * {@link IbmYamlEmitter#imageName} 주석에 있다.
 */
class IbmYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "ibm");
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.zone", "jp-tok-1");
        cfg.put("osImage", "ibm-ubuntu-24-04-4-minimal-amd64-7");
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
    @SuppressWarnings("unchecked")
    void theRequestedVpcCidrBecomesAnAddressPrefix() {
        /*
         * 기본값(auto)으로 두면 IBM 이 자기 대역으로 접두사를 만든다. 요청한 vpcCidr 은 그 안에
         * 들어가지 않아 서브넷이 "CIDR does not fit in any of the address prefixes" 로 거절된다.
         */
        Map<String, String> cfg = cfg();
        cfg.put("vpcCidr", "10.98.0.0/16");
        Map<String, Object> resources = (Map<String, Object>) doc(cfg).get("resources");

        Map<String, Object> vpc = (Map<String, Object>) ((Map<String, Object>) resources.get("vpc")).get("properties");
        assertThat(vpc).containsEntry("addressPrefixManagement", "manual");

        Map<String, Object> prefix =
                (Map<String, Object>) ((Map<String, Object>) resources.get("addressPrefix")).get("properties");
        assertThat(prefix).containsEntry("cidr", "10.98.0.0/16").containsEntry("zone", "jp-tok-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSubnetWaitsForTheAddressPrefix() {
        // 접두사보다 먼저 만들어지면 같은 이유로 거절된다.
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");
        Map<String, Object> options =
                (Map<String, Object>) ((Map<String, Object>) resources.get("subnet")).get("options");

        assertThat((List<String>) options.get("dependsOn")).contains("${addressPrefix}");
    }

    @Test
    void subnetNeedsZoneNotRegion() {
        // region 만으로는 서브넷을 만들 수 없다. zone 은 계정마다 활성 목록이 달라 추측하지 않는다.
        assertThat(props("subnet")).containsEntry("zone", "jp-tok-1");
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
