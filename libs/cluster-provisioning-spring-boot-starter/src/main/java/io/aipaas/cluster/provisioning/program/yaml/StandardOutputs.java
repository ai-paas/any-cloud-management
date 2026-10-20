package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표준 stack output 조립: {@code AbstractKubeadmProvisioner.assembleOutputs} 의 YAML 등가물.
 *
 * <p>이 키 목록이 {@code ProvisioningService.stackOutputs()} 의 계약. anycloud 의
 * {@code ProvisioningResultMapper}, {@code VmClusterNodeResolver},
 * {@code VmClusterPayloadServiceImpl} 이 이 이름으로 조회. 변경 시 소비자 동작 실패.
 */
public final class StandardOutputs {

    private StandardOutputs() {}

    /**
     * 인스턴스 하나의 YAML 리소스 이름과 속성 경로.
     *
     * <p>IP 를 리소스별로 분리한 이유. OpenStack 의 floating IP 와 Azure 의 private IP 는
     * 인스턴스가 아닌 별도 리소스에 연결. 인스턴스를 참조하면 빈 값 반환.
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
         * 요청에서 주소를 지정하는 CSP 용.
         *
         * <p>Proxmox 는 cloud 이미지에 {@code qemu-guest-agent} 가 없어 인스턴스 주소 조회 불가.
         * 고정 주소를 지정했으므로 해당 값 사용.
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
                // 현재 구현은 publicIp 를 그대로 사용. 계약 유지가 목적이므로 동작 변경 없음.
                .output("masterPublicDns", masterPublicIp)
                .output("apiServerUrl", "https://" + masterPublicIp + ":" + K8sConstants.PORT_KUBE_API_SERVER)
                /*
                 * 기본 사용자는 CSP 마다 다르다. Alibaba 의 Ubuntu 이미지에는 ubuntu 계정이 없고
                 * 키가 root 에 들어간다. 백엔드가 전역 기본값으로 붙으면 그 CSP 만 조용히 막힌다.
                 */
                .output("sshUser", spec.sshUser())
                .output("sshPrivateKeyPem", YamlRef.secret(YamlRef.of(refs.sshKeyResource(), "privateKeyPem")))
                .output("kubeconfigRemotePath", "/etc/kubernetes/admin.conf")
                .output("masterSshCommand", YamlRef.secret(sshCommand(spec, masterPublicIp)))
                .output("kubeconfigFetchCommand", YamlRef.secret(kubeconfigFetchCommand(spec, masterPublicIp)))
                .output("nodes", YamlRef.toJson(nodes(spec, refs)));
    }

    private static String sshCommand(ClusterSpec spec, String publicIp) {
        return sshCommand(spec, publicIp, K8sConstants.PORT_SSH);
    }

    /** 포트 지정 대상은 NAT 뒤 노드. 기본 포트면 인자를 생략해 기존 출력 형태 유지. */
    private static String sshCommand(ClusterSpec spec, String publicIp, int sshPort) {
        String port = sshPort == K8sConstants.PORT_SSH ? "" : "-p " + sshPort + " ";
        return "ssh " + port + "-i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp;
    }

    private static String kubeconfigFetchCommand(ClusterSpec spec, String publicIp) {
        return "ssh -i ./secrets/" + spec.name() + ".pem " + spec.sshUser() + "@" + publicIp
                + " \"sudo cat /etc/kubernetes/admin.conf\" > ./kubeconfig-" + spec.name();
    }

    /**
     * 배열 그대로 출력 금지. {@code WorkspaceStack.up} 이 내부 호출하는
     * {@code getStackOutputs} 가 문자열을 기대해 gson 단계에서 실패. YAML 경로도 SDK 경유.
     * 소비자({@code VmClusterNodeResolver})는 배열과 JSON 문자열 모두 수용.
     */
    private static List<Map<String, Object>> nodes(ClusterSpec spec, NodeRefs refs) {
        List<Map<String, Object>> nodes = new ArrayList<>(1 + refs.workers().size());
        nodes.add(nodeEntry(spec, "master", refs.master()));
        for (NodeRef worker : refs.workers()) {
            nodes.add(nodeEntry(spec, "worker", worker));
        }
        return nodes;
    }

    /** 키 이름은 {@code AbstractKubeadmProvisioner.nodeEntry} 와 동일 필요. */
    private static Map<String, Object> nodeEntry(ClusterSpec spec, String role, NodeRef ref) {
        String publicIp = ref.publicIp();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("instanceId", ref.instanceId());
        entry.put("privateIp", ref.privateIp());
        entry.put("publicIp", publicIp);
        entry.put("publicDns", publicIp);
        // 22 가 아닌 노드는 부트스트랩과 웹 콘솔이 이 값을 참조. 부재 시 22 로 접속.
        entry.put("sshPort", ref.sshPort());
        entry.put("ssh", sshCommand(spec, publicIp, ref.sshPort()));
        return entry;
    }
}
