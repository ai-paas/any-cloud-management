package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Alibaba Cloud ECS 리소스를 YAML 로 생성. */
final class AlibabaYamlEmitter implements ProviderYamlEmitter {

    private static final String T_VPC = "alicloud:vpc/network:Network";
    private static final String T_VSWITCH = "alicloud:vpc/switch:Switch";
    private static final String T_SECURITY_GROUP = "alicloud:ecs/securityGroup:SecurityGroup";
    private static final String T_SECURITY_GROUP_RULE = "alicloud:ecs/securityGroupRule:SecurityGroupRule";
    private static final String T_KEY_PAIR = "alicloud:ecs/keyPair:KeyPair";
    private static final String T_INSTANCE = "alicloud:ecs/instance:Instance";
    private static final String T_EIP = "alicloud:ecs/eipAddress:EipAddress";
    private static final String T_EIP_ASSOCIATION = "alicloud:ecs/eipAssociation:EipAssociation";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    /** Ubuntu 24.04 공식 이미지. 리전마다 ID 가 같아 이름으로 찾지 않아도 된다. */
    private static final String DEFAULT_IMAGE = "ubuntu_24_04_x64_20G_alibase_20250117.vhd";

    /** 클라우드 디스크. 최소 20GB 이며 ESSD 가 기본 선택지다. */
    private static final String SYSTEM_DISK_CATEGORY = "cloud_essd";

    /** EIP 대역폭(Mbps). 문자열로 받는다 — 스키마가 string 이다. */
    private static final String EIP_BANDWIDTH = "100";

    @Override
    public String name() {
        return "alibaba";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        ProviderSpec.Alibaba ali = alibaba(spec);
        requireConfig(ali.zone(), "providerSpec.zone");

        b.resource("sshKeyPair", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        b.resource(
                "sshKey",
                T_KEY_PAIR,
                Map.of(
                        "keyPairName", resourceName(spec, "key"),
                        "publicKey", YamlRef.of("sshKeyPair", "publicKeyOpenssh")));

        emitNetwork(b, spec, ali);
        emitSecurityGroup(b, spec);

        StandardOutputs.NodeRef master = emitNode(b, spec, ali, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, ali, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKeyPair", "vpc", "id", master, workers);
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Alibaba ali) {
        b.resource("vpc", T_VPC, Map.of("vpcName", resourceName(spec, "vpc"), "cidrBlock", spec.vpcCidr()));
        b.resource(
                "subnet",
                T_VSWITCH,
                Map.of(
                        "vswitchName", resourceName(spec, "vsw"),
                        "vpcId", YamlRef.of("vpc", "id"),
                        "cidrBlock", firstSubnet(spec),
                        "zoneId", ali.zone()));
    }

    private void emitSecurityGroup(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource(
                "secgroup",
                T_SECURITY_GROUP,
                Map.of("securityGroupName", resourceName(spec, "sg"), "vpcId", YamlRef.of("vpc", "id")));

        b.resource("sg-ssh", T_SECURITY_GROUP_RULE, ingress("tcp", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH));
        b.resource(
                "sg-api",
                T_SECURITY_GROUP_RULE,
                ingress("tcp", K8sConstants.PORT_KUBE_API_SERVER, K8sConstants.PORT_KUBE_API_SERVER));
        b.resource(
                "sg-nodeport",
                T_SECURITY_GROUP_RULE,
                ingress("tcp", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX));
        /*
         * 노드 간 트래픽은 프로토콜을 가리지 않고 연다. kubelet, etcd, Calico 가 TCP 와 UDP 를
         * 모두 쓰고, 포트를 하나씩 세면 새 구성 요소가 붙을 때마다 조용히 막힌다.
         */
        Map<String, Object> intra = new LinkedHashMap<>(ingress("all", 0, 0));
        intra.put("cidrIp", spec.vpcCidr());
        intra.remove("portRange");
        intra.put("portRange", "-1/-1");
        b.resource("sg-intra", T_SECURITY_GROUP_RULE, intra);
    }

    /**
     * {@code portRange} 는 {@code "22/22"} 형식의 문자열이다. 프로토콜이 {@code all} 이면
     * {@code "-1/-1"} 을 쓴다 — 숫자 범위를 주면 거절된다.
     */
    private Map<String, Object> ingress(String protocol, int from, int to) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("securityGroupId", YamlRef.of("secgroup", "id"));
        rule.put("type", "ingress");
        rule.put("ipProtocol", protocol);
        rule.put("portRange", "all".equals(protocol) ? "-1/-1" : from + "/" + to);
        rule.put("cidrIp", "0.0.0.0/0");
        // 규칙이 여러 개면 우선순위가 같아야 평가 순서가 정해진다. 기본값과 같은 값을 명시한다.
        rule.put("priority", 1);
        return rule;
    }

    private StandardOutputs.NodeRef emitNode(
            PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Alibaba ali, String node, String userData) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("instanceName", resourceName(spec, node));
        instance.put("hostName", resourceName(spec, node));
        instance.put("availabilityZone", ali.zone());
        instance.put("instanceType", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType());
        instance.put("imageId", imageId(spec));
        instance.put("vswitchId", YamlRef.of("subnet", "id"));
        instance.put("securityGroups", List.of(YamlRef.of("secgroup", "id")));
        instance.put("keyName", YamlRef.of("sshKey", "keyPairName"));
        instance.put("systemDiskCategory", SYSTEM_DISK_CATEGORY);
        instance.put("systemDiskSize", rootDiskGb(spec));
        instance.put("userData", userData);
        b.resource(node, T_INSTANCE, instance);

        /*
         * EIP 는 인스턴스와 따로 산다 — 인스턴스를 지워도 남아 계속 과금된다. 스택이 함께
         * 관리하므로 destroy 에서 같이 사라진다.
         */
        String eip = node + "-eip";
        b.resource(eip, T_EIP, Map.of("addressName", resourceName(spec, node + "-eip"), "bandwidth", EIP_BANDWIDTH));
        b.resource(
                node + "-eip-assoc",
                T_EIP_ASSOCIATION,
                Map.of("allocationId", YamlRef.of(eip, "id"), "instanceId", YamlRef.of(node, "id")));

        // 공인 주소는 EIP 에 붙는다. 인스턴스의 publicIp 는 EIP 를 쓰면 비어 있다.
        return new StandardOutputs.NodeRef(node, "id", node, "privateIp", eip, "ipAddress");
    }

    private String imageId(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : DEFAULT_IMAGE;
    }

    private int rootDiskGb(ClusterSpec spec) {
        // ECS 시스템 디스크 최소가 20GB 다. 그보다 작게 요청하면 생성이 거절된다.
        return Math.max(spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 50, 20);
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return spec.name() + "-" + suffix;
    }

    /** 노드가 한 서브넷에 모이므로 하나면 된다. */
    private static String firstSubnet(ClusterSpec spec) {
        List<String> cidrs = spec.subnetCidrs();
        return (cidrs == null || cidrs.isEmpty()) ? spec.vpcCidr() : cidrs.get(0);
    }

    private static void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 config 누락: " + key);
        }
    }

    /** provider 전용 설정. 다른 CSP 의 spec 이 오면 assembler 가 provider 를 잘못 라우팅한 것이다. */
    private static ProviderSpec.Alibaba alibaba(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Alibaba a) return a;
        throw new IllegalStateException("Alibaba 설정이 없다: provider=" + spec.provider());
    }
}
