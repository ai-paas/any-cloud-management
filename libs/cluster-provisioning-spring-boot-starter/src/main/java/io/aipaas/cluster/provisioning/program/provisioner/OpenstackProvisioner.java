package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import com.pulumi.openstack.compute.Instance;
import com.pulumi.openstack.compute.InstanceArgs;
import com.pulumi.openstack.compute.Keypair;
import com.pulumi.openstack.compute.KeypairArgs;
import com.pulumi.openstack.compute.inputs.InstanceNetworkArgs;
import com.pulumi.openstack.networking.FloatingIp;
import com.pulumi.openstack.networking.FloatingIpArgs;
import com.pulumi.openstack.networking.FloatingIpAssociate;
import com.pulumi.openstack.networking.FloatingIpAssociateArgs;
import com.pulumi.openstack.networking.Network;
import com.pulumi.openstack.networking.NetworkArgs;
import com.pulumi.openstack.networking.Port;
import com.pulumi.openstack.networking.PortArgs;
import com.pulumi.openstack.networking.Router;
import com.pulumi.openstack.networking.RouterArgs;
import com.pulumi.openstack.networking.RouterInterface;
import com.pulumi.openstack.networking.RouterInterfaceArgs;
import com.pulumi.openstack.networking.SecGroup;
import com.pulumi.openstack.networking.SecGroupArgs;
import com.pulumi.openstack.networking.SecGroupRule;
import com.pulumi.openstack.networking.SecGroupRuleArgs;
import com.pulumi.openstack.networking.Subnet;
import com.pulumi.openstack.networking.SubnetArgs;
import com.pulumi.openstack.networking.inputs.PortFixedIpArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import com.pulumi.tls.PrivateKey;
import com.pulumi.tls.PrivateKeyArgs;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.ResourceNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenStack (Neutron + Nova) provider. Go {@code infra/pulumi/pkg/providers/openstack/*} 등가물.
 *
 * <p>Network + Subnet + Router + RouterInterface (external network) + SecGroup + 5 rules.
 * 인스턴스 1대 = (Port + Compute + FloatingIp + Associate) 4 자원.
 *
 * <p>Spot 미지원 (no-op). FloatingIpPool / ExternalNetworkId 필수.
 */
public final class OpenstackProvisioner extends AbstractKubeadmProvisioner {

    @Override
    public String name() {
        return "openstack";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        if (spec.openstackExternalNetworkId() == null || spec.openstackExternalNetworkId().isBlank()) {
            throw new IllegalStateException("openstackExternalNetworkId is required for OpenStack provisioning");
        }
        if (spec.openstackFloatingIpPool() == null || spec.openstackFloatingIpPool().isBlank()) {
            throw new IllegalStateException("openstackFloatingIpPool is required for OpenStack provisioning");
        }

        NetworkResult net = provisionNetwork(spec);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder().algorithm("RSA").rsaBits(4096).build());

        Keypair keypair = new Keypair(
                resourceName(spec, "keypair"),
                KeypairArgs.builder()
                        .name(resourceName(spec, "keypair"))
                        .publicKey(privateKey.publicKeyOpenssh())
                        .region(spec.region())
                        .build());

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionInstance(spec, net, keypair, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException(
                    "OpenstackProvisioner: no master NodeSpec produced (masterCount=" + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionInstance(spec, net, keypair, node, masterInstance.resource()));
        }

        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.network.id(), Map.of());
    }

    // ===== Network =====

    private record NetworkResult(Network network, Subnet subnet, SecGroup sg) {}

    private NetworkResult provisionNetwork(ClusterSpec spec) {
        Network network = new Network(
                resourceName(spec, "network"),
                NetworkArgs.builder()
                        .name(resourceName(spec, "network"))
                        .adminStateUp(true)
                        .region(spec.region())
                        .build());

        Subnet subnet = new Subnet(
                resourceName(spec, "subnet"),
                SubnetArgs.builder()
                        .name(resourceName(spec, "subnet"))
                        .networkId(network.id())
                        .cidr(spec.subnetCidrs().get(0))
                        .ipVersion(4)
                        .region(spec.region())
                        .build());

        Router router = new Router(
                resourceName(spec, "router"),
                RouterArgs.builder()
                        .name(resourceName(spec, "router"))
                        .adminStateUp(true)
                        .externalNetworkId(spec.openstackExternalNetworkId())
                        .region(spec.region())
                        .build());

        new RouterInterface(
                resourceName(spec, "router-if"),
                RouterInterfaceArgs.builder()
                        .routerId(router.id())
                        .subnetId(subnet.id())
                        .region(spec.region())
                        .build());

        SecGroup sg = new SecGroup(
                resourceName(spec, "sg"),
                SecGroupArgs.builder()
                        .name(resourceName(spec, "sg"))
                        .description("anycloud kubernetes security group")
                        .deleteDefaultRules(false)
                        .region(spec.region())
                        .build());

        record Rule(String name, String protocol, int min, int max, String cidr) {}
        for (Rule rule : List.of(
                new Rule("ssh", "tcp", K8sConstants.PORT_SSH, K8sConstants.PORT_SSH, "0.0.0.0/0"),
                new Rule(
                        "k8s-api",
                        "tcp",
                        K8sConstants.PORT_KUBE_API_SERVER,
                        K8sConstants.PORT_KUBE_API_SERVER,
                        "0.0.0.0/0"),
                new Rule("nodeport", "tcp", K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX, "0.0.0.0/0"),
                new Rule("intra-tcp", "tcp", 1, 65535, spec.vpcCidr()),
                new Rule("intra-udp", "udp", 1, 65535, spec.vpcCidr()))) {
            new SecGroupRule(
                    resourceName(spec, "sg-rule-" + rule.name()),
                    SecGroupRuleArgs.builder()
                            .securityGroupId(sg.id())
                            .direction("ingress")
                            .ethertype("IPv4")
                            .protocol(rule.protocol())
                            .portRangeMin(rule.min())
                            .portRangeMax(rule.max())
                            .remoteIpPrefix(rule.cidr())
                            .region(spec.region())
                            .build());
        }
        return new NetworkResult(network, subnet, sg);
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec, NetworkResult net, Keypair keypair, NodeSpec node, Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);

        Port port = new Port(
                resourceName(spec, suffix + "-port"),
                PortArgs.builder()
                        .name(resourceName(spec, suffix + "-port"))
                        .networkId(net.network.id())
                        .adminStateUp(true)
                        .fixedIps(PortFixedIpArgs.builder().subnetId(net.subnet.id()).build())
                        .securityGroupIds(net.sg.id().applyValue(List::of))
                        .region(spec.region())
                        .build());

        String imageName = (node.osImage() != null && !node.osImage().isBlank())
                ? node.osImage()
                : (spec.openstackImageName() != null ? spec.openstackImageName() : "ubuntu-24.04");
        String flavorName = spec.openstackFlavorName() != null ? spec.openstackFlavorName() : node.instanceType();

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Instance instance = new Instance(
                resourceName(spec, suffix),
                InstanceArgs.builder()
                        .name(resourceName(spec, suffix))
                        .imageName(imageName)
                        .flavorName(flavorName)
                        .keyPair(keypair.name())
                        .securityGroups(net.sg.name().applyValue(List::of))
                        .userData(node.userData())
                        .networks(InstanceNetworkArgs.builder().port(port.id()).build())
                        .region(spec.region())
                        .build(),
                opts);

        FloatingIp floatingIp = new FloatingIp(
                resourceName(spec, suffix + "-fip"),
                FloatingIpArgs.builder()
                        .pool(spec.openstackFloatingIpPool())
                        .region(spec.region())
                        .build());

        new FloatingIpAssociate(
                resourceName(spec, suffix + "-fip-assoc"),
                FloatingIpAssociateArgs.builder()
                        .floatingIp(floatingIp.address())
                        .portId(port.id())
                        .region(spec.region())
                        .build());

        // accessIpV4 는 floating IP attach 후 채워짐 — 더 안정적인 source 는 floatingIp.address.
        return new InstanceOutput(instance, instance.id(), instance.accessIpV4(), floatingIp.address());
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }
}
