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
     * <p>IP 가 리소스별로 따로인 이유 — OpenStack 은 floating IP 가, Azure 는 private IP 가 인스턴스가
     * 아니라 별도 리소스에 붙는다. 인스턴스를 가리키면 값이 비어 나온다.
     */
    public record NodeRef(
            String resource,
            String instanceIdProperty,
            String privateIpResource,
            String privateIpProperty,
            String publicIpResource,
            String publicIpProperty,
            String privateIpLiteral,
            String publicIpLiteral,
            int sshPort) {

        public NodeRef(
                String resource,
                String instanceIdProperty,
                String privateIpResource,
                String privateIpProperty,
                String publicIpResource,
                String publicIpProperty) {
            this(
                    resource,
                    instanceIdProperty,
                    privateIpResource,
                    privateIpProperty,
                    publicIpResource,
                    publicIpProperty,
                    null,
                    null,
                    K8sConstants.PORT_SSH);
        }

        /** private IP 가 인스턴스에 붙는 CSP 용. */
        public NodeRef(
                String resource,
                String instanceIdProperty,
                String privateIpProperty,
                String publicIpResource,
                String publicIpProperty) {
            this(resource, instanceIdProperty, resource, privateIpProperty, publicIpResource, publicIpProperty);
        }

        /**
         * 주소를 요청에서 정한 CSP 용.
         *
         * <p>Proxmox 는 cloud 이미지에 {@code qemu-guest-agent} 가 없어 인스턴스에게 주소를 물을 수
         * 없다. 고정 주소를 넣었으므로 그 값을 그대로 쓴다.
         */
        public static NodeRef literal(
                String resource, String instanceIdProperty, String privateIp, String publicIp, int sshPort) {
            return new NodeRef(
                    resource, instanceIdProperty, resource, null, resource, null, privateIp, publicIp, sshPort);
        }

        String privateIp() {
            return privateIpLiteral != null ? privateIpLiteral : YamlRef.of(privateIpResource, privateIpProperty);
        }

        String publicIp() {
            return publicIpLiteral != null ? publicIpLiteral : YamlRef.of(publicIpResource, publicIpProperty);
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
                .output("nodes", YamlRef.toJson(nodes(spec, refs)));
    }

    private static String sshCommand(ClusterSpec spec, String publicIp) {
        return sshCommand(spec, publicIp, K8sConstants.PORT_SSH);
    }

    /** 포트를 붙이는 쪽은 NAT 뒤 노드다. 기본 포트면 인자를 넣지 않아 기존 출력과 같은 모양을 유지한다. */
    private static String sshCommand(ClusterSpec spec, String publicIp, int sshPort) {
        String port = sshPort == K8sConstants.PORT_SSH ? "" : "-p " + sshPort + " ";
        return "ssh " + port + "-i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp;
    }

    private static String kubeconfigFetchCommand(ClusterSpec spec, String publicIp) {
        return "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp
                + " \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-" + spec.name();
    }

    /**
     * 배열 그대로 내보내면 안 된다 — {@code WorkspaceStack.up} 이 내부에서 호출하는
     * {@code getStackOutputs} 가 문자열을 기대해 gson 단계에서 죽는다. YAML 경로도 SDK 를 거친다.
     * 소비자({@code VmClusterNodeResolver})는 배열과 JSON 문자열을 모두 받는다.
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
        // 22 가 아닌 노드는 부트스트랩과 웹 콘솔이 이 값을 봐야 한다. 없으면 22 로 붙는다.
        entry.put("sshPort", ref.sshPort());
        entry.put("ssh", sshCommand(spec, publicIp, ref.sshPort()));
        return entry;
    }
}
