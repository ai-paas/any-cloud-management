package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS VPC + EC2 리소스를 YAML 로 생성. */
final class AwsYamlEmitter implements ProviderYamlEmitter {

    private static final String T_VPC = "aws:ec2/vpc:Vpc";
    private static final String T_SUBNET = "aws:ec2/subnet:Subnet";
    private static final String T_IGW = "aws:ec2/internetGateway:InternetGateway";
    private static final String T_ROUTE_TABLE = "aws:ec2/routeTable:RouteTable";
    private static final String T_ROUTE_ASSOC = "aws:ec2/routeTableAssociation:RouteTableAssociation";
    private static final String T_SECURITY_GROUP = "aws:ec2/securityGroup:SecurityGroup";
    private static final String T_KEY_PAIR = "aws:ec2/keyPair:KeyPair";
    private static final String T_INSTANCE = "aws:ec2/instance:Instance";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String F_GET_AMI = "aws:ec2/getAmi:getAmi";

    /** Canonical, Inc. — Ubuntu 공식 AMI 소유자. */
    private static final String CANONICAL_OWNER = "099720109477";

    @Override
    public String name() {
        return "aws";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        emitAmiLookup(b, spec);
        emitSshKey(b, spec);
        emitNetwork(b, spec);
        emitSecurityGroup(b, spec);

        StandardOutputs.NodeRef master = emitNode(b, spec, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKey", "vpc", "id", master, workers);
    }

    /** osImage 가 없으면 최신 Ubuntu 24.04 를 찾는다. {@code return: id} 라 변수 자체가 AMI id 문자열이다. */
    private void emitAmiLookup(PulumiProgram.Builder b, ClusterSpec spec) {
        if (spec.osImage() != null && !spec.osImage().isBlank()) {
            return;
        }
        b.variable(
                "ami",
                YamlRef.invoke(
                        F_GET_AMI,
                        Map.of(
                                "mostRecent", true,
                                "owners", List.of(CANONICAL_OWNER),
                                "filters",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "name",
                                                        "values",
                                                        List.of(
                                                                "ubuntu/images/hvm-ssd-gp3/"
                                                                        + "ubuntu-noble-24.04-amd64-server-*")),
                                                Map.of("name", "virtualization-type", "values", List.of("hvm")),
                                                Map.of("name", "architecture", "values", List.of("x86_64")))),
                        "id"));
    }

    private void emitSshKey(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        b.resource(
                "keypair",
                T_KEY_PAIR,
                Map.of("keyName", spec.name() + "-key", "publicKey", YamlRef.of("sshKey", "publicKeyOpenssh")));
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource(
                "vpc",
                T_VPC,
                Map.of(
                        "cidrBlock",
                        spec.vpcCidr(),
                        "enableDnsHostnames",
                        true,
                        "enableDnsSupport",
                        true,
                        "tags",
                        Map.of("Name", spec.name() + "-vpc")));

        b.resource(
                "subnet",
                T_SUBNET,
                Map.of(
                        "vpcId", YamlRef.of("vpc", "id"),
                        "cidrBlock", firstSubnet(spec),
                        // 노드가 공인 IP 없이 뜨면 SSH bootstrap 도 이미지 pull 도 불가능하다.
                        "mapPublicIpOnLaunch", true,
                        "tags", Map.of("Name", spec.name() + "-subnet")));

        b.resource("igw", T_IGW, Map.of("vpcId", YamlRef.of("vpc", "id")));
        b.resource(
                "routeTable",
                T_ROUTE_TABLE,
                Map.of(
                        "vpcId", YamlRef.of("vpc", "id"),
                        "routes", List.of(Map.of("cidrBlock", "0.0.0.0/0", "gatewayId", YamlRef.of("igw", "id")))));
        b.resource(
                "routeAssoc",
                T_ROUTE_ASSOC,
                Map.of("subnetId", YamlRef.of("subnet", "id"), "routeTableId", YamlRef.of("routeTable", "id")));
    }

    private void emitSecurityGroup(PulumiProgram.Builder b, ClusterSpec spec) {
        List<Map<String, Object>> ingress = List.of(
                ingress("tcp", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH, "0.0.0.0/0"),
                ingress("tcp", K8sConstants.PORT_KUBE_API_SERVER, K8sConstants.PORT_KUBE_API_SERVER, "0.0.0.0/0"),
                ingress("tcp", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX, "0.0.0.0/0"),
                ingress("tcp", 1, 65535, spec.vpcCidr()),
                ingress("udp", 1, 65535, spec.vpcCidr()),
                // Calico 기본값 ipipMode=Always 는 노드 간 파드 트래픽을 IP protocol 4 로 감싼다.
                ingress("4", 0, 0, spec.vpcCidr()));

        b.resource(
                "secgroup",
                T_SECURITY_GROUP,
                Map.of(
                        "name",
                        spec.name() + "-sg",
                        "description",
                        spec.name() + " cluster nodes",
                        "vpcId",
                        YamlRef.of("vpc", "id"),
                        "ingress",
                        ingress,
                        "egress",
                        List.of(Map.of(
                                "protocol", "-1", "fromPort", 0, "toPort", 0, "cidrBlocks", List.of("0.0.0.0/0")))));
    }

    private Map<String, Object> ingress(String protocol, int from, int to, String cidr) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("protocol", protocol);
        rule.put("fromPort", from);
        rule.put("toPort", to);
        rule.put("cidrBlocks", List.of(cidr));
        return rule;
    }

    private StandardOutputs.NodeRef emitNode(PulumiProgram.Builder b, ClusterSpec spec, String node, String userData) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("ami", amiRef(spec));
        instance.put("instanceType", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType());
        instance.put("subnetId", YamlRef.of("subnet", "id"));
        instance.put("vpcSecurityGroupIds", List.of(YamlRef.of("secgroup", "id")));
        instance.put("keyName", YamlRef.of("keypair", "keyName"));
        instance.put("userData", userData);
        instance.put("associatePublicIpAddress", true);
        instance.put("rootBlockDevice", Map.of("volumeSize", rootDiskGb(spec), "volumeType", "gp3"));
        instance.put("tags", Map.of("Name", spec.name() + "-" + node));
        // route table 이 붙기 전에 뜨면 cloud-init 이 패키지 저장소에 닿지 못한다.
        b.resource(node, T_INSTANCE, instance, Map.of("dependsOn", List.of(YamlRef.resource("routeAssoc"))));

        return new StandardOutputs.NodeRef(node, "id", "privateIp", node, "publicIp");
    }

    private Object amiRef(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : YamlRef.resource("ami");
    }

    private int rootDiskGb(ClusterSpec spec) {
        return spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 50;
    }

    /** subnetCidrs 가 비면 VPC 전체를 쓴다 — 단일 서브넷 구성에서 흔한 요청 형태다. */
    private String firstSubnet(ClusterSpec spec) {
        List<String> cidrs = spec.subnetCidrs();
        return (cidrs == null || cidrs.isEmpty()) ? spec.vpcCidr() : cidrs.get(0);
    }
}
