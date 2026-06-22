package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.azurenative.compute.VirtualMachine;
import com.pulumi.azurenative.compute.VirtualMachineArgs;
import com.pulumi.azurenative.compute.inputs.HardwareProfileArgs;
import com.pulumi.azurenative.compute.inputs.ImageReferenceArgs;
import com.pulumi.azurenative.compute.inputs.LinuxConfigurationArgs;
import com.pulumi.azurenative.compute.inputs.NetworkInterfaceReferenceArgs;
import com.pulumi.azurenative.compute.inputs.NetworkProfileArgs;
import com.pulumi.azurenative.compute.inputs.OSDiskArgs;
import com.pulumi.azurenative.compute.inputs.OSProfileArgs;
import com.pulumi.azurenative.compute.inputs.SshConfigurationArgs;
import com.pulumi.azurenative.compute.inputs.SshPublicKeyArgs;
import com.pulumi.azurenative.compute.inputs.StorageProfileArgs;
import com.pulumi.azurenative.network.NetworkInterface;
import com.pulumi.azurenative.network.NetworkInterfaceArgs;
import com.pulumi.azurenative.network.NetworkSecurityGroup;
import com.pulumi.azurenative.network.NetworkSecurityGroupArgs;
import com.pulumi.azurenative.network.PublicIPAddress;
import com.pulumi.azurenative.network.PublicIPAddressArgs;
import com.pulumi.azurenative.network.SecurityRule;
import com.pulumi.azurenative.network.SecurityRuleArgs;
import com.pulumi.azurenative.network.Subnet;
import com.pulumi.azurenative.network.SubnetArgs;
import com.pulumi.azurenative.network.VirtualNetwork;
import com.pulumi.azurenative.network.VirtualNetworkArgs;
import com.pulumi.azurenative.network.inputs.AddressSpaceArgs;
import com.pulumi.azurenative.network.inputs.NetworkInterfaceIPConfigurationArgs;
import com.pulumi.azurenative.network.inputs.PublicIPAddressSkuArgs;
import com.pulumi.azurenative.resources.ResourceGroup;
import com.pulumi.azurenative.resources.ResourceGroupArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import com.pulumi.tls.PrivateKey;
import com.pulumi.tls.PrivateKeyArgs;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.ResourceNames;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure provider — azure-native API 기반.
 *
 * <p>Go 의 {@code pulumi-azure} (classic, terraform-azurerm 미러) → Java 의 {@code azure-native}
 * (ARM REST API 1:1). 자원 layout 동일하지만 builder/필드 명명 다름:
 *
 * <ul>
 *   <li>RG: {@code ResourceGroup} (위치 + 이름)
 *   <li>VNet/Subnet: separate resources; subnet 은 VNet 의 subResource
 *   <li>NSG: SecurityRule 을 separate resource 로 attach
 *   <li>NIC: ipConfigurations 에 publicIPAddress + subnet 참조
 *   <li>VM: HardwareProfile + OSProfile + NetworkProfile + StorageProfile 분리 (ARM 구조 그대로)
 *   <li>Spot: VMArgs.priority="Spot" + evictionPolicy="Deallocate"
 * </ul>
 */
public final class AzureProvisioner extends AbstractKubeadmProvisioner {

    private static final String IMG_PUBLISHER = "Canonical";
    private static final String IMG_OFFER = "ubuntu-24_04-lts";
    private static final String IMG_SKU = "server";
    private static final String IMG_VERSION = "latest";

    @Override
    public String name() {
        return "azure";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        if (spec.azureResourceGroup() == null || spec.azureResourceGroup().isBlank()) {
            throw new IllegalStateException("azureResourceGroup is required for Azure provisioning");
        }
        if (spec.region() == null || spec.region().isBlank()) {
            throw new IllegalStateException("region is required for Azure provisioning");
        }

        NetworkResult net = provisionNetwork(spec);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder().algorithm("RSA").rsaBits(4096).build());

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionInstance(spec, net, privateKey, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException(
                    "AzureProvisioner: no master NodeSpec produced (masterCount=" + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionInstance(spec, net, privateKey, node, masterInstance.resource()));
        }

        Map<String, Output<?>> extras = new LinkedHashMap<>();
        extras.put("resourceGroupName", net.rg.name());
        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.vnet.id(), extras);
    }

    // ===== Network =====

    private record NetworkResult(
            ResourceGroup rg, VirtualNetwork vnet, Subnet subnet, NetworkSecurityGroup nsg) {}

    private NetworkResult provisionNetwork(ClusterSpec spec) {
        ResourceGroup rg = new ResourceGroup(
                resourceName(spec, "rg"),
                ResourceGroupArgs.builder()
                        .resourceGroupName(spec.azureResourceGroup())
                        .location(spec.region())
                        .build());

        VirtualNetwork vnet = new VirtualNetwork(
                resourceName(spec, "vnet"),
                VirtualNetworkArgs.builder()
                        .resourceGroupName(rg.name())
                        .location(spec.region())
                        .virtualNetworkName(resourceName(spec, "vnet"))
                        .addressSpace(AddressSpaceArgs.builder()
                                .addressPrefixes(spec.vpcCidr())
                                .build())
                        .build());

        Subnet subnet = new Subnet(
                resourceName(spec, "subnet"),
                SubnetArgs.builder()
                        .resourceGroupName(rg.name())
                        .virtualNetworkName(vnet.name())
                        .subnetName(resourceName(spec, "subnet"))
                        .addressPrefix(spec.subnetCidrs().get(0))
                        .build());

        NetworkSecurityGroup nsg = new NetworkSecurityGroup(
                resourceName(spec, "nsg"),
                NetworkSecurityGroupArgs.builder()
                        .resourceGroupName(rg.name())
                        .location(spec.region())
                        .networkSecurityGroupName(resourceName(spec, "nsg"))
                        .build());

        // 3 inbound rules — SSH / kube-apiserver / NodePort range. Priority 100/110/120.
        record Rule(String name, int priority, String portRange) {}
        for (Rule r : List.of(
                new Rule("ssh", 100, String.valueOf(K8sConstants.PORT_SSH)),
                new Rule("k8s-api", 110, String.valueOf(K8sConstants.PORT_KUBE_API_SERVER)),
                new Rule(
                        "nodeport",
                        120,
                        K8sConstants.NODE_PORT_MIN + "-" + K8sConstants.NODE_PORT_MAX))) {
            new SecurityRule(
                    resourceName(spec, "nsg-rule-" + r.name()),
                    SecurityRuleArgs.builder()
                            .resourceGroupName(rg.name())
                            .networkSecurityGroupName(nsg.name())
                            .securityRuleName(r.name())
                            .access("Allow")
                            .direction("Inbound")
                            .priority(r.priority())
                            .protocol("Tcp")
                            .sourcePortRange("*")
                            .destinationPortRange(r.portRange())
                            .sourceAddressPrefix("*")
                            .destinationAddressPrefix("*")
                            .build());
        }
        return new NetworkResult(rg, vnet, subnet, nsg);
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec, NetworkResult net, PrivateKey privateKey, NodeSpec node, Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);

        PublicIPAddress publicIp = new PublicIPAddress(
                resourceName(spec, suffix + "-ip"),
                PublicIPAddressArgs.builder()
                        .resourceGroupName(net.rg.name())
                        .location(spec.region())
                        .publicIpAddressName(resourceName(spec, suffix + "-ip"))
                        .publicIPAllocationMethod("Static")
                        .sku(PublicIPAddressSkuArgs.builder().name("Standard").build())
                        .build());

        NetworkInterface nic = new NetworkInterface(
                resourceName(spec, suffix + "-nic"),
                NetworkInterfaceArgs.builder()
                        .resourceGroupName(net.rg.name())
                        .location(spec.region())
                        .networkInterfaceName(resourceName(spec, suffix + "-nic"))
                        .ipConfigurations(NetworkInterfaceIPConfigurationArgs.builder()
                                .name("internal")
                                .subnet(com.pulumi.azurenative.network.inputs.SubnetArgs.builder()
                                        .id(net.subnet.id())
                                        .build())
                                .privateIPAllocationMethod("Dynamic")
                                .publicIPAddress(
                                        com.pulumi.azurenative.network.inputs.PublicIPAddressArgs.builder()
                                                .id(publicIp.id())
                                                .build())
                                .build())
                        .networkSecurityGroup(
                                com.pulumi.azurenative.network.inputs.NetworkSecurityGroupArgs.builder()
                                        .id(net.nsg.id())
                                        .build())
                        .build());

        // Custom data — cloud-init shell script base64.
        Output<String> base64UserData = node.userData()
                .applyValue(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));

        VirtualMachineArgs.Builder vmArgsBuilder = VirtualMachineArgs.builder()
                .resourceGroupName(net.rg.name())
                .location(spec.region())
                .vmName(resourceName(spec, suffix))
                .hardwareProfile(HardwareProfileArgs.builder()
                        .vmSize(node.instanceType())
                        .build())
                .osProfile(OSProfileArgs.builder()
                        .computerName(resourceName(spec, suffix))
                        .adminUsername(spec.sshUser())
                        .customData(base64UserData)
                        .linuxConfiguration(LinuxConfigurationArgs.builder()
                                .disablePasswordAuthentication(true)
                                .ssh(SshConfigurationArgs.builder()
                                        .publicKeys(SshPublicKeyArgs.builder()
                                                .keyData(privateKey.publicKeyOpenssh())
                                                .path("/home/" + spec.sshUser() + "/.ssh/authorized_keys")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .networkProfile(NetworkProfileArgs.builder()
                        .networkInterfaces(NetworkInterfaceReferenceArgs.builder()
                                .id(nic.id())
                                .primary(true)
                                .build())
                        .build())
                .storageProfile(StorageProfileArgs.builder()
                        .imageReference(ImageReferenceArgs.builder()
                                .publisher(IMG_PUBLISHER)
                                .offer(IMG_OFFER)
                                .sku(IMG_SKU)
                                .version(IMG_VERSION)
                                .build())
                        .osDisk(OSDiskArgs.builder()
                                .createOption("FromImage")
                                .diskSizeGB(node.rootDiskSizeGb())
                                .caching(com.pulumi.azurenative.compute.enums.CachingTypes.ReadWrite)
                                .build())
                        .build());

        if (node.useSpot()) {
            // Azure Spot — capacity 회수 가능. Deallocate = stop 만 (재시작 가능).
            vmArgsBuilder.priority("Spot").evictionPolicy("Deallocate");
        }

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        VirtualMachine vm = new VirtualMachine(resourceName(spec, suffix), vmArgsBuilder.build(), opts);

        // Private IP — NIC.ipConfigurations() 가 Output<Optional<List<...>>> 반환. Optional 풀고 첫 element 의
        // privateIPAddress() (자체도 Optional<String>) 풀어 빈 문자열 fallback.
        Output<String> privateIp = nic.ipConfigurations().applyValue(opt -> opt
                .filter(cfgs -> !cfgs.isEmpty())
                .map(cfgs -> cfgs.get(0).privateIPAddress().orElse(""))
                .orElse(""));

        return new InstanceOutput(vm, vm.id(), privateIp, publicIp.ipAddress().applyValue(opt -> opt.orElse("")));
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }
}
