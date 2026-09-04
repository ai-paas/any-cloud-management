package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StandardOutputsTest {

    private ClusterSpec spec() {
        return ClusterSpec.from(Map.of(
                "provider", "openstack",
                "name", "demo",
                "sshUser", "ubuntu",
                "masterInstanceType", "m1.large",
                "workerInstanceType", "m1.large",
                "osImage", "ubuntu-24.04"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputs() {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.apply(
                b,
                spec(),
                new StandardOutputs.NodeRefs(
                        "sshKey",
                        "net",
                        "id",
                        new StandardOutputs.NodeRef("master", "id", "accessIpV4", "fip-master", "address"),
                        List.of(new StandardOutputs.NodeRef(
                                "worker-1", "id", "accessIpV4", "fip-worker-1", "address"))));
        Map<String, Object> doc = new Yaml().load(b.build().toYaml());
        return (Map<String, Object>) doc.get("outputs");
    }

    @Test
    void emitsExactlyTheContractKeys() {
        // 이 목록이 stackOutputs() 의 계약이다. 하나라도 빠지면 anycloud 가 깨진다.
        assertThat(outputs().keySet())
                .containsExactlyInAnyOrder(
                        "provider",
                        "clusterName",
                        "masterVmSpec",
                        "workerVmSpec",
                        "osImage",
                        "vpcId",
                        "masterInstanceId",
                        "masterPublicIp",
                        "masterPrivateIp",
                        "masterPublicDns",
                        "apiServerUrl",
                        "sshPrivateKeyPem",
                        "kubeconfigRemotePath",
                        "masterSshCommand",
                        "kubeconfigFetchCommand",
                        "nodes");
    }

    @Test
    void staticValuesComeFromSpec() {
        Map<String, Object> out = outputs();

        assertThat(out.get("provider")).isEqualTo("openstack");
        assertThat(out.get("clusterName")).isEqualTo("demo");
        assertThat(out.get("masterVmSpec")).isEqualTo("m1.large");
        assertThat(out.get("kubeconfigRemotePath")).isEqualTo("/etc/kubernetes/admin.conf");
    }

    @Test
    void resourceValuesUseInterpolation() {
        Map<String, Object> out = outputs();

        assertThat(out.get("masterInstanceId")).isEqualTo("${master.id}");
        assertThat(out.get("masterPrivateIp")).isEqualTo("${master.accessIpV4}");
        assertThat(out.get("vpcId")).isEqualTo("${net.id}");
    }

    @Test
    void publicIpComesFromFloatingIpResourceNotInstance() {
        // OpenStack 은 floating IP 가 별도 리소스다. 인스턴스를 가리키면 값이 비어 나온다.
        assertThat(outputs().get("masterPublicIp")).isEqualTo("${fip-master.address}");
    }

    @Test
    void masterPublicDnsMirrorsPublicIp() {
        // 현재 구현이 publicIp 를 그대로 넣는다. 계약 유지가 목적이므로 동작을 바꾸지 않는다.
        Map<String, Object> out = outputs();

        assertThat(out.get("masterPublicDns")).isEqualTo(out.get("masterPublicIp"));
    }

    @Test
    void apiServerUrlInterpolatesPublicIp() {
        assertThat(outputs().get("apiServerUrl")).isEqualTo("https://${fip-master.address}:6443");
    }

    @Test
    @SuppressWarnings("unchecked")
    void secretsAreWrapped() {
        // 키와 ssh 명령이 평문으로 상태에 남으면 안 된다.
        Map<String, Object> out = outputs();

        for (String key : List.of("sshPrivateKeyPem", "masterSshCommand", "kubeconfigFetchCommand")) {
            assertThat(out.get(key)).as("%s must be secret", key).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) out.get(key)).containsKey("fn::secret");
        }
    }

    @Test
    void nonSecretsAreNotWrapped() {
        assertThat(outputs().get("masterPublicIp")).isInstanceOf(String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodesIsRealArray() {
        // JSON 문자열은 Pulumi Java SDK 회피책이었다. YAML 경로는 SDK 를 거치지 않으므로 불필요하고,
        // 소비자(VmClusterNodeResolver)가 배열을 기대한다.
        Object nodes = outputs().get("nodes");

        assertThat(nodes).isInstanceOf(List.class);
        assertThat((List<Object>) nodes).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodesFieldNamesMatchExistingContract() {
        // AbstractKubeadmProvisioner.nodeEntry 와 같은 키여야 한다.
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) outputs().get("nodes");

        assertThat(nodes.get(0)).containsKeys("role", "instanceId", "privateIp", "publicIp", "publicDns", "ssh");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodesCarryMasterAndWorkerWithInterpolation() {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) outputs().get("nodes");

        assertThat(nodes.get(0)).containsEntry("role", "master").containsEntry("publicIp", "${fip-master.address}");
        assertThat(nodes.get(1)).containsEntry("role", "worker").containsEntry("publicIp", "${fip-worker-1.address}");
    }
}
