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
        // 이 목록이 stackOutputs() 의 계약. 하나라도 누락되면 anycloud 동작 실패.
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
                        "sshUser",
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
        // OpenStack 의 floating IP 는 별도 리소스. 인스턴스 참조 시 빈 값 반환.
        assertThat(outputs().get("masterPublicIp")).isEqualTo("${fip-master.address}");
    }

    @Test
    void masterPublicDnsMirrorsPublicIp() {
        // 현재 구현은 publicIp 를 그대로 사용. 계약 유지가 목적이므로 동작 변경 없음.
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
        // 키와 ssh 명령의 평문 상태 저장 금지.
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

    /** {@code fn::toJSON} 내부 배열. 직렬화는 Pulumi 가 실행 시점에 수행. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodeEntries() {
        Map<String, Object> wrapper = (Map<String, Object>) outputs().get("nodes");
        return (List<Map<String, Object>>) wrapper.get("fn::toJSON");
    }

    @Test
    void nodesCarryEveryNode() {
        assertThat(nodeEntries()).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodesFieldNamesMatchExistingContract() {
        // AbstractKubeadmProvisioner.nodeEntry 와 동일 키 필요.
        assertThat(nodeEntries().get(0))
                .containsKeys("role", "instanceId", "privateIp", "publicIp", "publicDns", "ssh");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodesCarryMasterAndWorkerWithInterpolation() {
        assertThat(nodeEntries().get(0))
                .containsEntry("role", "master")
                .containsEntry("publicIp", "${fip-master.address}");
        assertThat(nodeEntries().get(1))
                .containsEntry("role", "worker")
                .containsEntry("publicIp", "${fip-worker-1.address}");
    }

    @Test
    void everyOutputIsScalarOrKnownFunction() {
        // WorkspaceStack.up 이 내부 호출하는 getStackOutputs 는 배열, 객체 output 수신 시
        // gson 단계에서 실패. YAML 경로도 SDK 를 경유하므로 동일 적용.
        outputs().forEach((key, value) -> {
            if (value == null) {
                return; // gson 은 JSON null 을 그대로 받는다
            }
            if (value instanceof Map<?, ?> fn) {
                assertThat(fn.keySet())
                        .as("output %s 은 fn:: 함수 하나여야 한다", key)
                        .allSatisfy(k -> assertThat(String.valueOf(k)).startsWith("fn::"));
            } else {
                assertThat(value).as("output %s", key).isInstanceOf(String.class);
            }
        });
    }

    @Test
    void nodesIsWrappedInToJson() {
        Map<?, ?> nodes = (Map<?, ?>) outputs().get("nodes");

        assertThat(nodes).hasSize(1);
        assertThat(nodes.containsKey("fn::toJSON")).isTrue();
    }
}
