package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Azure VNet + Virtual Machine 리소스를 YAML 로 생성. */
final class AzureYamlEmitter implements ProviderYamlEmitter {

    private static final String T_RESOURCE_GROUP = "azure-native:resources:ResourceGroup";
    private static final String T_VNET = "azure-native:network:VirtualNetwork";
    private static final String T_SUBNET = "azure-native:network:Subnet";
    private static final String T_NSG = "azure-native:network:NetworkSecurityGroup";
    private static final String T_SECURITY_RULE = "azure-native:network:SecurityRule";
    private static final String T_PUBLIC_IP = "azure-native:network:PublicIPAddress";
    private static final String T_NIC = "azure-native:network:NetworkInterface";
    private static final String T_VM = "azure-native:compute:VirtualMachine";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String DEFAULT_IMAGE = "Canonical:ubuntu-24_04-lts:server:latest";

    /** computerName 은 Linux 에서 64자를 넘지 못하고 점을 받지 않는다. */
    private static final int COMPUTER_NAME_MAX = 63;

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        String resourceGroup = azure(spec).resourceGroup();
        requireConfig(resourceGroup, "providerSpec.resourceGroup");
        requireConfig(spec.region(), "region");

        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        emitNetwork(b, spec, resourceGroup);

        StandardOutputs.NodeRef master = emitNode(b, spec, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKey", "vnet", "id", master, workers);
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec, String resourceGroup) {
        b.resource("rg", T_RESOURCE_GROUP, Map.of("resourceGroupName", resourceGroup, "location", spec.region()));

        b.resource(
                "vnet",
                T_VNET,
                Map.of(
                        "resourceGroupName", YamlRef.of("rg", "name"),
                        "location", spec.region(),
                        "virtualNetworkName", spec.name() + "-vnet",
                        "addressSpace", Map.of("addressPrefixes", List.of(spec.vpcCidr()))));

        b.resource(
                "subnet",
                T_SUBNET,
                Map.of(
                        "resourceGroupName", YamlRef.of("rg", "name"),
                        "virtualNetworkName", YamlRef.of("vnet", "name"),
                        "subnetName", spec.name() + "-subnet",
                        "addressPrefix", firstSubnet(spec)));

        b.resource(
                "nsg",
                T_NSG,
                Map.of(
                        "resourceGroupName", YamlRef.of("rg", "name"),
                        "location", spec.region(),
                        "networkSecurityGroupName", spec.name() + "-nsg"));

        emitSecurityRules(b, spec);
    }

    private void emitSecurityRules(PulumiProgram.Builder b, ClusterSpec spec) {
        b.resource("nsg-rule-ssh", T_SECURITY_RULE, tcpRule(spec, "ssh", 100, String.valueOf(K8sConstants.PORT_SSH)));
        b.resource(
                "nsg-rule-k8s-api",
                T_SECURITY_RULE,
                tcpRule(spec, "k8s-api", 110, String.valueOf(K8sConstants.PORT_KUBE_API_SERVER)));
        b.resource(
                "nsg-rule-nodeport",
                T_SECURITY_RULE,
                tcpRule(spec, "nodeport", 120, K8sConstants.NODE_PORT_MIN + "-" + K8sConstants.NODE_PORT_MAX));

        // Azure NSG 는 프로토콜을 Tcp/Udp/Icmp/Esp/Ah/* 로만 받는다. Calico 기본값 ipipMode=Always 가
        // 쓰는 IP protocol 4 를 지정할 방법이 없어 VNet 내부는 * 로 연다.
        Map<String, Object> intraVnet = ruleBase(spec, "intra-vnet", 130, "*");
        intraVnet.put("sourceAddressPrefix", spec.vpcCidr());
        intraVnet.put("destinationPortRange", "*");
        b.resource("nsg-rule-intra-vnet", T_SECURITY_RULE, intraVnet);
    }

    private Map<String, Object> tcpRule(ClusterSpec spec, String name, int priority, String portRange) {
        Map<String, Object> rule = ruleBase(spec, name, priority, "Tcp");
        rule.put("sourceAddressPrefix", "*");
        rule.put("destinationPortRange", portRange);
        return rule;
    }

    private Map<String, Object> ruleBase(ClusterSpec spec, String name, int priority, String protocol) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("resourceGroupName", YamlRef.of("rg", "name"));
        rule.put("networkSecurityGroupName", YamlRef.of("nsg", "name"));
        rule.put("securityRuleName", name);
        rule.put("access", "Allow");
        rule.put("direction", "Inbound");
        rule.put("priority", priority);
        rule.put("protocol", protocol);
        rule.put("sourcePortRange", "*");
        rule.put("destinationAddressPrefix", "*");
        return rule;
    }

    private StandardOutputs.NodeRef emitNode(PulumiProgram.Builder b, ClusterSpec spec, String node, String userData) {
        String ip = "ip-" + node;
        String nic = "nic-" + node;

        b.resource(
                ip,
                T_PUBLIC_IP,
                Map.of(
                        "resourceGroupName", YamlRef.of("rg", "name"),
                        "location", spec.region(),
                        "publicIpAddressName", spec.name() + "-" + node + "-ip",
                        // Dynamic 은 VM 이 뜬 뒤에야 값이 잡혀 output 이 비어 나온다.
                        "publicIPAllocationMethod", "Static",
                        "sku", Map.of("name", "Standard")));

        b.resource(
                nic,
                T_NIC,
                Map.of(
                        "resourceGroupName",
                        YamlRef.of("rg", "name"),
                        "location",
                        spec.region(),
                        "networkInterfaceName",
                        spec.name() + "-" + node + "-nic",
                        "ipConfigurations",
                        List.of(Map.of(
                                "name",
                                "internal",
                                "subnet",
                                Map.of("id", YamlRef.of("subnet", "id")),
                                "privateIPAllocationMethod",
                                "Dynamic",
                                "publicIPAddress",
                                Map.of("id", YamlRef.of(ip, "id")))),
                        "networkSecurityGroup",
                        Map.of("id", YamlRef.of("nsg", "id"))));

        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("resourceGroupName", YamlRef.of("rg", "name"));
        vm.put("location", spec.region());
        vm.put("vmName", spec.name() + "-" + node);
        vm.put(
                "hardwareProfile",
                Map.of("vmSize", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType()));
        // Azure 는 customData 를 base64 로만 받는다. 평문을 넣으면 cloud-init 이 그대로 무시한다.
        vm.put(
                "osProfile",
                Map.of(
                        "computerName",
                        computerName(spec, node),
                        "adminUsername",
                        sshUser(spec),
                        "customData",
                        YamlRef.toBase64(userData),
                        "linuxConfiguration",
                        Map.of(
                                "disablePasswordAuthentication",
                                true,
                                "ssh",
                                Map.of(
                                        "publicKeys",
                                        List.of(Map.of(
                                                "keyData",
                                                YamlRef.of("sshKey", "publicKeyOpenssh"),
                                                "path",
                                                "/home/" + sshUser(spec) + "/.ssh/authorized_keys"))))));
        vm.put("networkProfile", Map.of("networkInterfaces", List.of(Map.of("id", YamlRef.of(nic, "id")))));
        vm.put(
                "storageProfile",
                Map.of(
                        "imageReference", imageReference(spec),
                        "osDisk",
                                Map.of(
                                        "createOption", "FromImage",
                                        "diskSizeGB", rootDiskGb(spec),
                                        "caching", "ReadWrite")));
        b.resource(node, T_VM, vm);

        return new StandardOutputs.NodeRef(node, "id", nic, "ipConfigurations[0].privateIPAddress", ip, "ipAddress");
    }

    /** {@code publisher:offer:sku:version} 4단 좌표. Azure 는 이미지를 단일 ID 로 지정하지 않는다. */
    private Map<String, Object> imageReference(ClusterSpec spec) {
        String raw = (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : DEFAULT_IMAGE;
        String[] parts = raw.split(":");
        if (parts.length != 4) {
            throw new IllegalStateException("osImage 는 publisher:offer:sku:version 형식이어야 한다: " + raw);
        }
        return Map.of("publisher", parts[0], "offer", parts[1], "sku", parts[2], "version", parts[3]);
    }

    /** Linux computerName 은 63자 이하 영숫자와 하이픈만 받는다. */
    private String computerName(ClusterSpec spec, String node) {
        String raw = (spec.name() + "-" + node).replaceAll("[^a-zA-Z0-9-]", "-");
        return raw.length() > COMPUTER_NAME_MAX ? raw.substring(0, COMPUTER_NAME_MAX) : raw;
    }

    private String sshUser(ClusterSpec spec) {
        return (spec.sshUser() != null && !spec.sshUser().isBlank()) ? spec.sshUser() : "ubuntu";
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
    private static ProviderSpec.Azure azure(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Azure a) return a;
        throw new IllegalStateException("Azure 설정이 없다: provider=" + spec.provider());
    }
}
