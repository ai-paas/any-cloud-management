package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OCI VCN + Compute 리소스를 YAML 로 생성. */
final class OciYamlEmitter implements ProviderYamlEmitter {

    private static final String T_VCN = "oci:Core/vcn:Vcn";
    private static final String T_SUBNET = "oci:Core/subnet:Subnet";
    private static final String T_IGW = "oci:Core/internetGateway:InternetGateway";
    private static final String T_ROUTE_TABLE = "oci:Core/defaultRouteTable:DefaultRouteTable";
    private static final String T_SECURITY_LIST = "oci:Core/defaultSecurityList:DefaultSecurityList";
    private static final String T_INSTANCE = "oci:Core/instance:Instance";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String F_GET_ADS = "oci:Identity/getAvailabilityDomains:getAvailabilityDomains";

    /** IANA 프로토콜 번호. OCI security list 는 이름이 아니라 번호를 받는다. */
    private static final String PROTO_TCP = "6";

    private static final String PROTO_UDP = "17";
    private static final String PROTO_IPIP = "4";

    @Override
    public String name() {
        return "oci";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        String compartment = oci(spec).compartmentId();
        requireConfig(compartment, "providerSpec.compartmentId");

        b.variable("ads", YamlRef.invoke(F_GET_ADS, Map.of("compartmentId", compartment), null));
        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        emitNetwork(b, spec, compartment);

        StandardOutputs.NodeRef master = emitNode(b, spec, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKey", "vcn", "id", master, workers);
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec, String compartment) {
        b.resource(
                "vcn",
                T_VCN,
                Map.of(
                        "compartmentId",
                        compartment,
                        "cidrBlocks",
                        List.of(spec.vpcCidr()),
                        "displayName",
                        spec.name() + "-vcn",
                        "dnsLabel",
                        dnsLabel(spec)));

        b.resource(
                "igw",
                T_IGW,
                Map.of(
                        "compartmentId",
                        compartment,
                        "vcnId",
                        YamlRef.of("vcn", "id"),
                        "displayName",
                        spec.name() + "-igw",
                        "enabled",
                        true));

        // VCN 이 만든 기본 route table / security list 를 덮어쓴다. 별도로 만들면 서브넷이
        // 여전히 기본 것을 물고 있어 규칙이 적용되지 않는다.
        b.resource(
                "routeTable",
                T_ROUTE_TABLE,
                Map.of(
                        "manageDefaultResourceId", YamlRef.of("vcn", "defaultRouteTableId"),
                        "routeRules",
                                List.of(Map.of(
                                        "destination", "0.0.0.0/0",
                                        "destinationType", "CIDR_BLOCK",
                                        "networkEntityId", YamlRef.of("igw", "id")))));

        b.resource(
                "securityList",
                T_SECURITY_LIST,
                Map.of(
                        "manageDefaultResourceId", YamlRef.of("vcn", "defaultSecurityListId"),
                        "egressSecurityRules", List.of(Map.of("destination", "0.0.0.0/0", "protocol", "all")),
                        "ingressSecurityRules", ingressRules(spec)));

        b.resource(
                "subnet",
                T_SUBNET,
                Map.of(
                        "compartmentId", compartment,
                        "vcnId", YamlRef.of("vcn", "id"),
                        "cidrBlock", firstSubnet(spec),
                        "displayName", spec.name() + "-subnet",
                        "routeTableId", YamlRef.of("vcn", "defaultRouteTableId"),
                        "securityListIds", List.of(YamlRef.of("vcn", "defaultSecurityListId"))));
    }

    private List<Map<String, Object>> ingressRules(ClusterSpec spec) {
        return List.of(
                tcpRule("0.0.0.0/0", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH),
                tcpRule("0.0.0.0/0", K8sConstants.PORT_KUBE_API_SERVER, K8sConstants.PORT_KUBE_API_SERVER),
                tcpRule("0.0.0.0/0", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX),
                tcpRule(spec.vpcCidr(), 1, 65535),
                Map.of("source", spec.vpcCidr(), "protocol", PROTO_UDP),
                // Calico 기본값 ipipMode=Always 가 노드 간 파드 트래픽을 IP protocol 4 로 감싼다.
                Map.of("source", spec.vpcCidr(), "protocol", PROTO_IPIP));
    }

    private Map<String, Object> tcpRule(String source, int from, int to) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source);
        rule.put("protocol", PROTO_TCP);
        rule.put("tcpOptions", Map.of("min", from, "max", to));
        return rule;
    }

    private StandardOutputs.NodeRef emitNode(PulumiProgram.Builder b, ClusterSpec spec, String node, String userData) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("compartmentId", oci(spec).compartmentId());
        instance.put("availabilityDomain", YamlRef.of("ads", "availabilityDomains[0].name"));
        instance.put("displayName", spec.name() + "-" + node);
        instance.put("shape", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType());
        instance.put(
                "createVnicDetails",
                Map.of(
                        "subnetId",
                        YamlRef.of("subnet", "id"),
                        "assignPublicIp",
                        "true",
                        "displayName",
                        spec.name() + "-" + node + "-vnic"));
        instance.put(
                "sourceDetails",
                Map.of(
                        "sourceType", "image",
                        "sourceId", requireImage(spec),
                        "bootVolumeSizeInGbs", String.valueOf(rootDiskGb(spec))));
        // OCI 는 user-data 를 base64 로만 받는다. 평문을 넣으면 cloud-init 이 그대로 무시한다.
        instance.put(
                "metadata",
                Map.of(
                        "ssh_authorized_keys", YamlRef.of("sshKey", "publicKeyOpenssh"),
                        "user_data", YamlRef.toBase64(userData)));
        b.resource(node, T_INSTANCE, instance);

        return new StandardOutputs.NodeRef(node, "id", "privateIp", node, "publicIp");
    }

    /**
     * OCI 는 이미지 OCID 를 리전마다 따로 발급한다. 이름으로 찾는 안정된 필터가 없어 osImage 를
     * 필수로 받는다 — 추측한 OCID 로 만들면 엉뚱한 이미지가 뜬다.
     */
    private String requireImage(ClusterSpec spec) {
        requireConfig(spec.osImage(), "osImage (OCI image OCID)");
        return spec.osImage();
    }

    /** dnsLabel 은 영숫자만 받고 15자를 넘지 못한다. */
    private String dnsLabel(ClusterSpec spec) {
        String raw = spec.name().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (raw.isEmpty()) raw = "cluster";
        return raw.length() > 15 ? raw.substring(0, 15) : raw;
    }

    private int rootDiskGb(ClusterSpec spec) {
        return spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 50;
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
    private static ProviderSpec.Oci oci(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Oci o) return o;
        throw new IllegalStateException("Oci 설정이 없다: provider=" + spec.provider());
    }
}
