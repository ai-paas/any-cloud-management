package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IBM Cloud VPC 리소스를 YAML 로 생성.
 *
 * <p>다른 CSP 와 달리 사전 컴파일된 Pulumi 플러그인이 없다. {@code terraform-provider} 베이스가
 * OpenTofu 레지스트리의 provider 를 런타임에 붙인다. 그래서 프로그램에 {@code packages} 선언이
 * 필요하고, 이미지에 플러그인을 미리 넣어 둘 수 없다.
 */
final class IbmYamlEmitter implements ProviderYamlEmitter {

    private static final String T_VPC = "ibm:index/isVpc:IsVpc";
    private static final String T_SUBNET = "ibm:index/isSubnet:IsSubnet";
    private static final String T_SECURITY_GROUP = "ibm:index/isSecurityGroup:IsSecurityGroup";
    private static final String T_SECURITY_GROUP_RULE = "ibm:index/isSecurityGroupRule:IsSecurityGroupRule";
    private static final String T_SSH_KEY = "ibm:index/isSshKey:IsSshKey";
    private static final String T_PUBLIC_GATEWAY = "ibm:index/isPublicGateway:IsPublicGateway";
    private static final String T_FLOATING_IP = "ibm:index/isFloatingIp:IsFloatingIp";
    private static final String T_INSTANCE = "ibm:index/isInstance:IsInstance";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String F_GET_IMAGE = "ibm:index/getIsImage:getIsImage";

    private static final String DEFAULT_IMAGE_NAME = "ibm-ubuntu-24-04-6-minimal-amd64-6";

    /** Dockerfile 의 IBM_PACKAGE, 그리고 이미지에 구워 둔 스키마와 같은 좌표여야 한다. */
    private static final String TF_BASE_VERSION = "1.4.0";

    private static final String TF_PARAMETER = "ibm-cloud/ibm";

    @Override
    public String name() {
        return "ibm";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        ProviderSpec.Ibm ibm = ibm(spec);
        requireConfig(ibm.zone(), "providerSpec.zone");

        b.pkg("ibm", "terraform-provider", TF_BASE_VERSION, List.of(TF_PARAMETER));
        b.variable("image", YamlRef.invoke(F_GET_IMAGE, Map.of("name", imageName(spec)), "id"));
        b.resource("sshKeyPair", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        b.resource(
                "sshKey",
                T_SSH_KEY,
                withResourceGroup(
                        ibm,
                        Map.of(
                                "name", resourceName(spec, "key"),
                                "publicKey", YamlRef.of("sshKeyPair", "publicKeyOpenssh"),
                                "type", "rsa")));

        emitNetwork(b, spec, ibm);
        emitSecurityGroup(b, spec, ibm);

        StandardOutputs.NodeRef master = emitNode(b, spec, ibm, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, ibm, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKeyPair", "vpc", "id", master, workers);
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Ibm ibm) {
        b.resource("vpc", T_VPC, withResourceGroup(ibm, Map.of("name", resourceName(spec, "vpc"))));

        // 인터넷 egress 가 없으면 cloud-init 이 패키지 저장소에 닿지 못한다.
        b.resource(
                "gateway",
                T_PUBLIC_GATEWAY,
                withResourceGroup(
                        ibm,
                        Map.of(
                                "name", resourceName(spec, "gw"),
                                "vpc", YamlRef.of("vpc", "id"),
                                "zone", ibm.zone())));

        b.resource(
                "subnet",
                T_SUBNET,
                withResourceGroup(
                        ibm,
                        Map.of(
                                "name", resourceName(spec, "subnet"),
                                "vpc", YamlRef.of("vpc", "id"),
                                "zone", ibm.zone(),
                                "ipv4CidrBlock", firstSubnet(spec),
                                "publicGateway", YamlRef.of("gateway", "id"))));
    }

    private void emitSecurityGroup(PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Ibm ibm) {
        b.resource(
                "secgroup",
                T_SECURITY_GROUP,
                withResourceGroup(ibm, Map.of("name", resourceName(spec, "sg"), "vpc", YamlRef.of("vpc", "id"))));

        b.resource("sg-egress", T_SECURITY_GROUP_RULE, rule("outbound", "0.0.0.0/0", null, 0, 0));
        b.resource(
                "sg-ssh",
                T_SECURITY_GROUP_RULE,
                rule("inbound", "0.0.0.0/0", "tcp", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH));
        b.resource(
                "sg-api",
                T_SECURITY_GROUP_RULE,
                rule(
                        "inbound",
                        "0.0.0.0/0",
                        "tcp",
                        K8sConstants.PORT_KUBE_API_SERVER,
                        K8sConstants.PORT_KUBE_API_SERVER));
        b.resource(
                "sg-nodeport",
                T_SECURITY_GROUP_RULE,
                rule("inbound", "0.0.0.0/0", "tcp", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX));
        // Calico 기본값 ipipMode=Always 가 노드 간 파드 트래픽을 IP protocol 4 로 감싼다. IBM 은
        // protocol 을 tcp/udp/icmp/all 로만 받아 VPC 내부를 all 로 연다.
        b.resource("sg-intra", T_SECURITY_GROUP_RULE, rule("inbound", spec.vpcCidr(), null, 0, 0));
    }

    /** {@code protocol} 이 null 이면 all — IBM 은 "all" 문자열 대신 필드 생략으로 표현한다. */
    private Map<String, Object> rule(String direction, String remote, String protocol, int from, int to) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("group", YamlRef.of("secgroup", "id"));
        r.put("direction", direction);
        r.put("remote", remote);
        if (protocol != null) {
            r.put(protocol, Map.of("portMin", from, "portMax", to));
        }
        return r;
    }

    private StandardOutputs.NodeRef emitNode(
            PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Ibm ibm, String node, String userData) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("name", resourceName(spec, node));
        instance.put("vpc", YamlRef.of("vpc", "id"));
        instance.put("zone", ibm.zone());
        instance.put("profile", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType());
        instance.put("image", YamlRef.resource("image"));
        instance.put("keys", List.of(YamlRef.of("sshKey", "id")));
        instance.put("userData", userData);
        instance.put("bootVolume", Map.of("size", rootDiskGb(spec)));
        instance.put(
                "primaryNetworkInterface",
                Map.of(
                        "subnet", YamlRef.of("subnet", "id"),
                        "securityGroups", List.of(YamlRef.of("secgroup", "id"))));
        if (ibm.resourceGroup() != null && !ibm.resourceGroup().isBlank()) {
            instance.put("resourceGroup", ibm.resourceGroup());
        }
        b.resource(node, T_INSTANCE, instance);

        // IBM 은 인스턴스에 공인 IP 를 직접 붙이지 않는다. floating IP 를 NIC 에 연결한다.
        String fip = "fip-" + node;
        b.resource(
                fip,
                T_FLOATING_IP,
                withResourceGroup(
                        ibm,
                        Map.of(
                                "name", resourceName(spec, node + "-fip"),
                                "target", YamlRef.of(node, "primaryNetworkInterface.id"))));

        return new StandardOutputs.NodeRef(
                node, "id", node, "primaryNetworkInterface.primaryIpv4Address", fip, "address");
    }

    private Map<String, Object> withResourceGroup(ProviderSpec.Ibm ibm, Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        if (ibm.resourceGroup() != null && !ibm.resourceGroup().isBlank()) {
            out.put("resourceGroup", ibm.resourceGroup());
        }
        return out;
    }

    private String imageName(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : DEFAULT_IMAGE_NAME;
    }

    private String resourceName(ClusterSpec spec, String suffix) {
        return spec.name() + "-" + suffix;
    }

    private int rootDiskGb(ClusterSpec spec) {
        return spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 100;
    }

    private String firstSubnet(ClusterSpec spec) {
        List<String> cidrs = spec.subnetCidrs();
        return (cidrs == null || cidrs.isEmpty()) ? spec.vpcCidr() : cidrs.get(0);
    }

    private static void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 config 누락: " + key);
        }
    }

    /** provider 전용 설정. 다른 CSP 의 spec 이 오면 assembler 가 provider 를 잘못 라우팅한 것이다. */
    private static ProviderSpec.Ibm ibm(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Ibm i) return i;
        throw new IllegalStateException("Ibm 설정이 없다: provider=" + spec.provider());
    }
}
