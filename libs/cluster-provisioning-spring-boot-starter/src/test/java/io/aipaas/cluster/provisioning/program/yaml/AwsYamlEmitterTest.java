package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** AWS YAML 프로그램 회귀 보호. 타입 토큰과 참조 형태는 CLI 가 preview 에서만 잡아준다. */
class AwsYamlEmitterTest {

    private ClusterSpec spec() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "aws");
        cfg.put("name", "demo");
        cfg.put("region", "ap-northeast-2");
        cfg.put("workerCount", "2");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc() {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        new AwsYamlEmitter().emit(b, spec());
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String resource) {
        Map<String, Object> resources = (Map<String, Object>) doc().get("resources");
        Map<String, Object> entry = (Map<String, Object>) resources.get(resource);
        return (Map<String, Object>) entry.get("properties");
    }

    @SuppressWarnings("unchecked")
    private String type(String resource) {
        Map<String, Object> resources = (Map<String, Object>) doc().get("resources");
        return (String) ((Map<String, Object>) resources.get(resource)).get("type");
    }

    @Test
    void emitsEveryNodeAndNetworkResource() {
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc().get("resources");

        assertThat(resources)
                .containsKeys(
                        "sshKey",
                        "keypair",
                        "vpc",
                        "subnet",
                        "igw",
                        "routeTable",
                        "routeAssoc",
                        "secgroup",
                        "master",
                        "worker-1",
                        "worker-2");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        // 토큰이 틀리면 preview 에서 "resolving type" 으로 죽는다. 스키마로 확인한 값이다.
        assertThat(type("vpc")).isEqualTo("aws:ec2/vpc:Vpc");
        assertThat(type("subnet")).isEqualTo("aws:ec2/subnet:Subnet");
        assertThat(type("secgroup")).isEqualTo("aws:ec2/securityGroup:SecurityGroup");
        assertThat(type("master")).isEqualTo("aws:ec2/instance:Instance");
        assertThat(type("keypair")).isEqualTo("aws:ec2/keyPair:KeyPair");
    }

    @Test
    @SuppressWarnings("unchecked")
    void amiVariableIsAlreadyAnIdString() {
        // return: id 를 쓰므로 변수 자체가 문자열이다. ${ami.id} 로 쓰면 preview 가 거부한다.
        Map<String, Object> variables = (Map<String, Object>) doc().get("variables");

        assertThat(variables).containsKey("ami");
        assertThat(props("master").get("ami")).isEqualTo("${ami}");
    }

    @Test
    void explicitOsImageSkipsLookup() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "aws");
        cfg.put("name", "demo");
        cfg.put("osImage", "ami-0123456789abcdef0");
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        new AwsYamlEmitter().emit(b, Defaults.applyProviderDefaults(ClusterSpec.from(cfg)));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
                (Map<String, Object>) new Yaml().load(b.build().toYaml());
        assertThat(parsed).doesNotContainKey("variables");
    }

    @Test
    void securityGroupAllowsIpipForCalico() {
        // TCP/UDP 만 열면 노드 간 파드 트래픽이 통째로 사라진다 — OpenStack 에서 실제로 겪었다.
        @SuppressWarnings("unchecked")
        var ingress = (java.util.List<Map<String, Object>>) props("secgroup").get("ingress");

        assertThat(ingress).anySatisfy(rule -> assertThat(rule).containsEntry("protocol", "4"));
    }

    @Test
    void nodesWaitForRouteAssociation() {
        // route table 이 붙기 전에 뜨면 cloud-init 이 패키지 저장소에 닿지 못한다.
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc().get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> master = (Map<String, Object>) resources.get("master");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) master.get("options");

        assertThat(options.get("dependsOn").toString()).contains("${routeAssoc}");
    }

    @Test
    void publicIpIsAssignedSoBootstrapCanReachNodes() {
        assertThat(props("subnet")).containsEntry("mapPublicIpOnLaunch", true);
        assertThat(props("master")).containsEntry("associatePublicIpAddress", true);
    }
}
