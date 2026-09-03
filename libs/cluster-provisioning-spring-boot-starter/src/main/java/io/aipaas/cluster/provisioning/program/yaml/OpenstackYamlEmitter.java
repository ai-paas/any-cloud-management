package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenStack 리소스 정의. {@code OpenstackProvisioner} 의 YAML 등가물.
 *
 * <p>type token 과 속성 이름은 provider 스키마({@code pulumi package get-schema openstack})에서
 * 확인한 값이다. 추측하면 preview 에서 unknown resource type 으로 드러난다.
 */
final class OpenstackYamlEmitter implements ProviderYamlEmitter {

    private static final String T_NETWORK = "openstack:networking/network:Network";
    private static final String T_SUBNET = "openstack:networking/subnet:Subnet";
    private static final String T_ROUTER = "openstack:networking/router:Router";
    private static final String T_ROUTER_IFACE = "openstack:networking/routerInterface:RouterInterface";
    private static final String T_SECGROUP = "openstack:networking/secGroup:SecGroup";
    private static final String T_SECGROUP_RULE = "openstack:networking/secGroupRule:SecGroupRule";
    private static final String T_PORT = "openstack:networking/port:Port";
    private static final String T_INSTANCE = "openstack:compute/instance:Instance";
    private static final String T_FLOATING_IP = "openstack:networking/floatingIp:FloatingIp";
    private static final String T_FIP_ASSOCIATE = "openstack:networking/floatingIpAssociate:FloatingIpAssociate";
    private static final String T_KEYPAIR = "openstack:compute/keypair:Keypair";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String DEFAULT_IMAGE = "ubuntu-24.04";

    @Override
    public String name() {
        return "openstack";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        requireConfig(spec.openstackExternalNetworkId(), "openstackExternalNetworkId");
        requireConfig(spec.openstackFloatingIpPool(), "openstackFloatingIpPool");

        emitSshKey(b, spec);
        emitNetwork(b, spec);
        emitSecurityGroup(b, spec);

        StandardOutputs.NodeRef master = emitNode(b, spec, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKey", "net", "id", master, workers);
    }

    private void emitSshKey(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        b.resource(
                "keypair",
                T_KEYPAIR,
                Map.of(
                        "name", spec.name() + "-key",
                        "publicKey", YamlRef.of("sshKey", "publicKeyOpenssh"),
                        "region", spec.region()));
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource("net", T_NETWORK, Map.of("name", spec.name() + "-net", "adminStateUp", true, "region", spec.region()));
        b.resource(
                "subnet",
                T_SUBNET,
                Map.of(
                        "name", spec.name() + "-subnet",
                        "networkId", YamlRef.of("net", "id"),
                        "cidr", firstSubnetCidr(spec),
                        "ipVersion", 4,
                        "region", spec.region()));
        b.resource(
                "router",
                T_ROUTER,
                Map.of(
                        "name", spec.name() + "-router",
                        "externalNetworkId", spec.openstackExternalNetworkId(),
                        "region", spec.region()));
        b.resource(
                "routerIface",
                T_ROUTER_IFACE,
                Map.of(
                        "routerId", YamlRef.of("router", "id"),
                        "subnetId", YamlRef.of("subnet", "id"),
                        "region", spec.region()));
    }

    private void emitSecurityGroup(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource(
                "secgroup",
                T_SECGROUP,
                Map.of(
                        "name", spec.name() + "-sg",
                        "description", "anycloud kubeadm cluster",
                        "region", spec.region()));
        rule(b, spec, "ssh", "tcp", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH, "0.0.0.0/0");
        rule(
                b,
                spec,
                "apiserver",
                "tcp",
                K8sConstants.PORT_KUBE_API_SERVER,
                K8sConstants.PORT_KUBE_API_SERVER,
                "0.0.0.0/0");
        rule(b, spec, "nodeport", "tcp", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX, "0.0.0.0/0");
        rule(b, spec, "intra-tcp", "tcp", 1, 65535, spec.vpcCidr());
        rule(b, spec, "intra-udp", "udp", 1, 65535, spec.vpcCidr());
    }

    private void rule(
            PulumiProgram.Builder b, ClusterSpec spec, String id, String proto, int from, int to, String cidr) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("securityGroupId", YamlRef.of("secgroup", "id"));
        props.put("direction", "ingress");
        props.put("ethertype", "IPv4");
        props.put("protocol", proto);
        props.put("portRangeMin", from);
        props.put("portRangeMax", to);
        props.put("remoteIpPrefix", cidr);
        props.put("region", spec.region());
        b.resource("sgrule-" + id, T_SECGROUP_RULE, props);
    }

    private StandardOutputs.NodeRef emitNode(
            PulumiProgram.Builder b, ClusterSpec spec, String node, String userData) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("name", spec.name() + "-" + node + "-port");
        port.put("networkId", YamlRef.of("net", "id"));
        port.put("adminStateUp", true);
        port.put("securityGroupIds", List.of(YamlRef.of("secgroup", "id")));
        port.put("fixedIps", List.of(Map.of("subnetId", YamlRef.of("subnet", "id"))));
        port.put("region", spec.region());
        // router interface 가 붙기 전에 포트를 만들면 외부 통신이 안 되는 상태로 인스턴스가 뜬다.
        b.resource("port-" + node, T_PORT, port, Map.of("dependsOn", List.of(YamlRef.of("routerIface", "id"))));

        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("name", spec.name() + "-" + node);
        instance.put("imageName", imageName(spec));
        instance.put("flavorName", flavorName(spec));
        instance.put("keyPair", YamlRef.of("keypair", "name"));
        instance.put("userData", userData);
        instance.put("networks", List.of(Map.of("port", YamlRef.of("port-" + node, "id"))));
        instance.put("region", spec.region());
        b.resource(node, T_INSTANCE, instance);

        b.resource(
                "fip-" + node,
                T_FLOATING_IP,
                Map.of("pool", spec.openstackFloatingIpPool(), "region", spec.region()));
        // 스키마의 FloatingIpAssociate 는 portId 만 받는다 — instanceId 라는 속성은 없다.
        b.resource(
                "fipassoc-" + node,
                T_FIP_ASSOCIATE,
                Map.of(
                        "floatingIp", YamlRef.of("fip-" + node, "address"),
                        "portId", YamlRef.of("port-" + node, "id"),
                        "region", spec.region()));

        // accessIpV4 는 floating IP attach 후에야 채워진다. 더 안정적인 source 는 fip.address 다.
        return new StandardOutputs.NodeRef(node, "id", "accessIpV4", "fip-" + node, "address");
    }

    private String imageName(ClusterSpec spec) {
        String image = spec.openstackImageName();
        if (image != null && !image.isBlank()) {
            return image;
        }
        String osImage = spec.osImage();
        return osImage != null && !osImage.isBlank() ? osImage : DEFAULT_IMAGE;
    }

    private String flavorName(ClusterSpec spec) {
        String flavor = spec.openstackFlavorName();
        return flavor != null && !flavor.isBlank() ? flavor : spec.workerInstanceType();
    }

    private String firstSubnetCidr(ClusterSpec spec) {
        List<String> cidrs = spec.subnetCidrs();
        return cidrs == null || cidrs.isEmpty() ? spec.vpcCidr() : cidrs.get(0);
    }

    /** preview 까지 가지 말고 즉시 실패한다 — 원인이 스택 로그에 묻히면 진단이 오래 걸린다. */
    private static void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for OpenStack provisioning");
        }
    }
}
