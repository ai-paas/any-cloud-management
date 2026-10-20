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

    /** 클라우드 디스크. 최소 20GB 이며 ESSD 가 기본 선택지다. */
    private static final String SYSTEM_DISK_CATEGORY = "cloud_essd";

    /** EIP 대역폭(Mbps). 스키마가 string 이라 문자열로 수신. */
    private static final String EIP_BANDWIDTH = "100";

    @Override
    public String name() {
        return "alibaba";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        ProviderSpec.Alibaba ali = alibaba(spec);
        requireConfig(ali.zone(), "providerSpec.zone");
        /*
         * 이미지 ID 에 빌드 날짜가 포함돼 주기적으로 교체되고(..._alibase_20260828.vhd)
         * 리전별로 상이. getImages 사용 시 결과가 배열이라 인덱스 접근이 필요한데 pulumi-yaml
         * 이 해당 경로의 타입을 해석하지 못해 타입 검사에서 panic. OCI 와 동일하게 필수 수신.
         * 화면은 이미지 목록에서 선택한 값을 그대로 전송.
         */
        requireConfig(spec.osImage(), "osImage");

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
     * {@code portRange} 는 {@code "22/22"} 형식 문자열. 프로토콜이 {@code all} 이면
     * {@code "-1/-1"} 사용. 숫자 범위 전달 시 거절.
     */
    private Map<String, Object> ingress(String protocol, int from, int to) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("securityGroupId", YamlRef.of("secgroup", "id"));
        rule.put("type", "ingress");
        rule.put("ipProtocol", protocol);
        rule.put("portRange", "all".equals(protocol) ? "-1/-1" : from + "/" + to);
        rule.put("cidrIp", "0.0.0.0/0");
        // 규칙이 여러 개면 우선순위가 동일해야 평가 순서 확정. 기본값과 동일 값 명시.
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
         * EIP 는 인스턴스와 독립 자원. 인스턴스 삭제 후에도 잔존해 과금 지속. 스택이 함께
         * 관리하므로 destroy 시 동시 삭제.
         */
        String eip = node + "-eip";
        b.resource(eip, T_EIP, Map.of("addressName", resourceName(spec, node + "-eip"), "bandwidth", EIP_BANDWIDTH));
        b.resource(
                node + "-eip-assoc",
                T_EIP_ASSOCIATION,
                Map.of("allocationId", YamlRef.of(eip, "id"), "instanceId", YamlRef.of(node, "id")));

        // 공인 주소는 EIP 에 할당. EIP 사용 시 인스턴스의 publicIp 는 빈 값.
        return new StandardOutputs.NodeRef(node, "id", node, "privateIp", eip, "ipAddress");
    }

    /** 요청이 이미지를 지정하면 그대로 쓰고, 아니면 조회 결과를 가리킨다. */
    private Object imageId(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : YamlRef.resource("image");
    }

    private int rootDiskGb(ClusterSpec spec) {
        // ECS 시스템 디스크 최소 20GB. 미만 요청 시 생성 거절.
        return Math.max(spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 50, 20);
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return spec.name() + "-" + suffix;
    }

    /** 전체 노드가 단일 서브넷에 배치되므로 1개로 충분. */
    private static String firstSubnet(ClusterSpec spec) {
        List<String> cidrs = spec.subnetCidrs();
        return (cidrs == null || cidrs.isEmpty()) ? spec.vpcCidr() : cidrs.get(0);
    }

    private static void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 config 누락: " + key);
        }
    }

    /** provider 전용 설정. 다른 CSP 의 spec 수신은 assembler 의 provider 라우팅 오류. */
    private static ProviderSpec.Alibaba alibaba(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Alibaba a) return a;
        throw new IllegalStateException("Alibaba 설정이 없다: provider=" + spec.provider());
    }
}
