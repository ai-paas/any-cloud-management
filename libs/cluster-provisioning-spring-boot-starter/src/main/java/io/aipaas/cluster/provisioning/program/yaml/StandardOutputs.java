package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표준 stack output 조립 — {@code AbstractKubeadmProvisioner.assembleOutputs} 의 YAML 등가물.
 *
 * <p>여기 키 목록이 {@code ProvisioningService.stackOutputs()} 의 계약이다. anycloud 의
 * {@code ProvisioningResultMapper}, {@code VmClusterNodeResolver},
 * {@code VmClusterPayloadServiceImpl} 이 이 이름으로 읽는다. 하나라도 바뀌면 소비자가 깨진다.
 */
public final class StandardOutputs {

    private StandardOutputs() {}

    /**
     * 인스턴스 하나의 YAML 리소스 이름과 속성 경로.
     *
     * <p>publicIp 만 리소스가 따로인 이유 — OpenStack 은 floating IP 가 인스턴스가 아니라 별도
     * 리소스다. 인스턴스를 가리키면 값이 비어 나온다.
     */
    public record NodeRef(
            String resource,
            String instanceIdProperty,
            String privateIpProperty,
            String publicIpResource,
            String publicIpProperty) {

        String privateIp() {
            return YamlRef.of(resource, privateIpProperty);
        }

        String publicIp() {
            return YamlRef.of(publicIpResource, publicIpProperty);
        }

        String instanceId() {
            return YamlRef.of(resource, instanceIdProperty);
        }
    }

    /** 출력 조립에 필요한 리소스 참조 묶음. */
    public record NodeRefs(
            String sshKeyResource, String vpcResource, String vpcProperty, NodeRef master, List<NodeRef> workers) {}

    public static void apply(PulumiProgram.Builder builder, ClusterSpec spec, NodeRefs refs) {
        String masterPublicIp = refs.master().publicIp();

        builder.output("provider", spec.provider())
                .output("clusterName", spec.name())
                .output("masterVmSpec", spec.masterInstanceType())
                .output("workerVmSpec", spec.workerInstanceType())
                .output("osImage", spec.osImageOrDefault())
                .output("vpcId", YamlRef.of(refs.vpcResource(), refs.vpcProperty()))
                .output("masterInstanceId", refs.master().instanceId())
                .output("masterPublicIp", masterPublicIp)
                .output("masterPrivateIp", refs.master().privateIp())
                // 현재 구현이 publicIp 를 그대로 넣는다. 계약 유지가 목적이라 동작을 바꾸지 않는다.
                .output("masterPublicDns", masterPublicIp)
                .output("apiServerUrl", "https://" + masterPublicIp + ":" + K8sConstants.PORT_KUBE_API_SERVER)
                .output("sshPrivateKeyPem", YamlRef.secret(YamlRef.of(refs.sshKeyResource(), "privateKeyPem")))
                .output("kubeconfigRemotePath", "/etc/kubernetes/admin.conf")
                .output("masterSshCommand", YamlRef.secret(sshCommand(spec, masterPublicIp)))
                .output("kubeconfigFetchCommand", YamlRef.secret(kubeconfigFetchCommand(spec, masterPublicIp)))
                .output("nodes", nodes(spec, refs));
    }

    private static String sshCommand(ClusterSpec spec, String publicIp) {
        return "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp;
    }

    private static String kubeconfigFetchCommand(ClusterSpec spec, String publicIp) {
        return "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp
                + " \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-" + spec.name();
    }

    /**
     * nodes 는 실제 배열이다.
     *
     * <p>JSON 문자열 형태는 Pulumi Java SDK 의 일부 역직렬화 경로가 배열 값을 못 다뤄 쓰던 회피책이다.
     * YAML 경로는 SDK 를 거치지 않고 CLI 의 stack output 을 읽으므로 그 제약이 없다.
     * 소비자({@code VmClusterNodeResolver})도 배열을 기대한다.
     */
    private static List<Map<String, Object>> nodes(ClusterSpec spec, NodeRefs refs) {
        List<Map<String, Object>> nodes = new ArrayList<>(1 + refs.workers().size());
        nodes.add(nodeEntry(spec, "master", refs.master()));
        for (NodeRef worker : refs.workers()) {
            nodes.add(nodeEntry(spec, "worker", worker));
        }
        return nodes;
    }

    /** 키 이름은 {@code AbstractKubeadmProvisioner.nodeEntry} 와 같아야 한다. */
    private static Map<String, Object> nodeEntry(ClusterSpec spec, String role, NodeRef ref) {
        String publicIp = ref.publicIp();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("instanceId", ref.instanceId());
        entry.put("privateIp", ref.privateIp());
        entry.put("publicIp", publicIp);
        entry.put("publicDns", publicIp);
        entry.put("ssh", sshCommand(spec, publicIp));
        return entry;
    }
}
