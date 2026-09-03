package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import com.pulumi.oci.Core.DefaultRouteTable;
import com.pulumi.oci.Core.DefaultRouteTableArgs;
import com.pulumi.oci.Core.DefaultSecurityList;
import com.pulumi.oci.Core.DefaultSecurityListArgs;
import com.pulumi.oci.Core.Instance;
import com.pulumi.oci.Core.InstanceArgs;
import com.pulumi.oci.Core.InternetGateway;
import com.pulumi.oci.Core.InternetGatewayArgs;
import com.pulumi.oci.Core.Subnet;
import com.pulumi.oci.Core.SubnetArgs;
import com.pulumi.oci.Core.Vcn;
import com.pulumi.oci.Core.VcnArgs;
import com.pulumi.oci.Core.inputs.DefaultRouteTableRouteRuleArgs;
import com.pulumi.oci.Core.inputs.DefaultSecurityListEgressSecurityRuleArgs;
import com.pulumi.oci.Core.inputs.DefaultSecurityListIngressSecurityRuleArgs;
import com.pulumi.oci.Core.inputs.DefaultSecurityListIngressSecurityRuleTcpOptionsArgs;
import com.pulumi.oci.Core.inputs.InstanceCreateVnicDetailsArgs;
import com.pulumi.oci.Core.inputs.InstanceSourceDetailsArgs;
import com.pulumi.oci.Core.inputs.InstanceSourceDetailsInstanceSourceImageFilterDetailsArgs;
import com.pulumi.oci.Identity.DynamicGroup;
import com.pulumi.oci.Identity.DynamicGroupArgs;
import com.pulumi.oci.Identity.IdentityFunctions;
import com.pulumi.oci.Identity.Policy;
import com.pulumi.oci.Identity.PolicyArgs;
import com.pulumi.oci.Identity.inputs.GetAvailabilityDomainsArgs;
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
 * Oracle Cloud Infrastructure provider. Go {@code infra/pulumi/pkg/providers/oci/*} 등가물.
 *
 * <p>VCN + InternetGateway + DefaultRouteTable + DefaultSecurityList + Subnet + Compute Instance.
 * CCM 권한은 Dynamic Group + Policy 로 instance-principal 제공. Spot/preemptible 미지원 (no-op).
 */
public final class OciProvisioner extends AbstractKubeadmProvisioner {

    @Override
    public String name() {
        return "oci";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        if (spec.region() == null || spec.region().isBlank()) {
            throw new IllegalStateException("region is required for OCI provisioning");
        }
        if (spec.ociCompartmentId() == null || spec.ociCompartmentId().isBlank()) {
            throw new IllegalStateException("ociCompartmentId is required for OCI provisioning");
        }
        String compartmentId = spec.ociCompartmentId();

        Output<String> adName = IdentityFunctions.getAvailabilityDomains(GetAvailabilityDomainsArgs.builder()
                        .compartmentId(compartmentId)
                        .build())
                .applyValue(r -> r.availabilityDomains().get(0).name());

        NetworkResult net = provisionNetwork(spec, compartmentId);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder().algorithm("RSA").rsaBits(4096).build());

        DynamicGroup dynGroup = new DynamicGroup(
                resourceName(spec, "dyngroup"),
                DynamicGroupArgs.builder()
                        .compartmentId(compartmentId)
                        .description("Anycloud K8s cloud-controller-manager dynamic group")
                        .matchingRule("ALL {instance.compartment.id = '" + compartmentId + "'}")
                        .name(resourceName(spec, "dyngroup"))
                        .build());

        new Policy(
                resourceName(spec, "ccm-policy"),
                PolicyArgs.builder()
                        .compartmentId(compartmentId)
                        .name(resourceName(spec, "ccm-policy"))
                        .description("Allow K8s cloud-controller-manager to manage compute/network/blockstorage")
                        .statements(dynGroup.name().applyValue(dg -> List.of(
                                "Allow dynamic-group " + dg + " to manage instance-family in compartment id " + compartmentId,
                                "Allow dynamic-group " + dg + " to manage virtual-network-family in compartment id " + compartmentId,
                                "Allow dynamic-group " + dg + " to manage volume-family in compartment id " + compartmentId,
                                "Allow dynamic-group " + dg + " to manage load-balancers in compartment id " + compartmentId)))
                        .build());

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionInstance(spec, net, compartmentId, adName, privateKey, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException("OciProvisioner: no master NodeSpec produced");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(
                    provisionInstance(spec, net, compartmentId, adName, privateKey, node, masterInstance.resource()));
        }

        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.vcn.id(), Map.of());
    }

    // ===== Network =====

    private record NetworkResult(Vcn vcn, Subnet subnet) {}

    private NetworkResult provisionNetwork(ClusterSpec spec, String compartmentId) {
        Vcn vcn = new Vcn(
                resourceName(spec, "vcn"),
                VcnArgs.builder()
                        .cidrBlock(spec.vpcCidr())
                        .compartmentId(compartmentId)
                        .displayName(resourceName(spec, "vcn"))
                        .dnsLabel("anycloud")
                        .build());

        InternetGateway igw = new InternetGateway(
                resourceName(spec, "igw"),
                InternetGatewayArgs.builder()
                        .compartmentId(compartmentId)
                        .vcnId(vcn.id())
                        .displayName(resourceName(spec, "igw"))
                        .enabled(true)
                        .build());

        new DefaultRouteTable(
                resourceName(spec, "rt"),
                DefaultRouteTableArgs.builder()
                        .manageDefaultResourceId(vcn.defaultRouteTableId())
                        .routeRules(DefaultRouteTableRouteRuleArgs.builder()
                                .networkEntityId(igw.id())
                                .destination("0.0.0.0/0")
                                .destinationType("CIDR_BLOCK")
                                .build())
                        .build());

        new DefaultSecurityList(
                resourceName(spec, "sl"),
                DefaultSecurityListArgs.builder()
                        .manageDefaultResourceId(vcn.defaultSecurityListId())
                        .ingressSecurityRules(List.of(
                                tcpIngress(K8sConstants.PORT_SSH, K8sConstants.PORT_SSH),
                                tcpIngress(K8sConstants.PORT_KUBE_API_SERVER, K8sConstants.PORT_KUBE_API_SERVER),
                                tcpIngress(K8sConstants.NODE_PORT_MIN, K8sConstants.NODE_PORT_MAX),
                                DefaultSecurityListIngressSecurityRuleArgs.builder()
                                        .protocol("all")
                                        .source(spec.vpcCidr())
                                        .build()))
                        .egressSecurityRules(DefaultSecurityListEgressSecurityRuleArgs.builder()
                                .protocol("all")
                                .destination("0.0.0.0/0")
                                .build())
                        .build());

        Subnet subnet = new Subnet(
                resourceName(spec, "subnet"),
                SubnetArgs.builder()
                        .cidrBlock(spec.subnetCidrs().get(0))
                        .compartmentId(compartmentId)
                        .vcnId(vcn.id())
                        .displayName(resourceName(spec, "subnet"))
                        .dnsLabel("anycsubnet")
                        .build());

        return new NetworkResult(vcn, subnet);
    }

    private static DefaultSecurityListIngressSecurityRuleArgs tcpIngress(int min, int max) {
        // OCI protocol "6" = TCP.
        return DefaultSecurityListIngressSecurityRuleArgs.builder()
                .protocol("6")
                .source("0.0.0.0/0")
                .tcpOptions(DefaultSecurityListIngressSecurityRuleTcpOptionsArgs.builder()
                        .min(min)
                        .max(max)
                        .build())
                .build();
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec,
            NetworkResult net,
            String compartmentId,
            Output<String> adName,
            PrivateKey privateKey,
            NodeSpec node,
            Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);

        // userData 는 base64 encode 후 metadata.userData 로 전달.
        Output<String> base64UserData =
                node.userData().applyValue(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
        Output<Map<String, String>> metadata = Output.tuple(privateKey.publicKeyOpenssh(), base64UserData)
                .applyValue(t -> Map.of("ssh_authorized_keys", t.t1, "user_data", t.t2));

        InstanceSourceDetailsArgs sourceDetails = resolveSource(compartmentId, node);

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Instance instance = new Instance(
                resourceName(spec, suffix),
                InstanceArgs.builder()
                        .availabilityDomain(adName)
                        .compartmentId(compartmentId)
                        .displayName(resourceName(spec, suffix))
                        .shape(node.instanceType())
                        .metadata(metadata)
                        .createVnicDetails(InstanceCreateVnicDetailsArgs.builder()
                                .subnetId(net.subnet.id())
                                .assignPublicIp("true")
                                .displayName(resourceName(spec, suffix + "-vnic"))
                                .build())
                        .sourceDetails(sourceDetails)
                        .build(),
                opts);

        return new InstanceOutput(instance, instance.id(), instance.privateIp(), instance.publicIp());
    }

    private InstanceSourceDetailsArgs resolveSource(String compartmentId, NodeSpec node) {
        String override = node.osImage();
        String bootVolumeGb = String.valueOf(node.rootDiskSizeGb());
        if (override != null && override.startsWith("ocid1.")) {
            return InstanceSourceDetailsArgs.builder()
                    .sourceType("image")
                    .sourceId(override)
                    .bootVolumeSizeInGbs(bootVolumeGb)
                    .build();
        }
        return InstanceSourceDetailsArgs.builder()
                .sourceType("image")
                .bootVolumeSizeInGbs(bootVolumeGb)
                .instanceSourceImageFilterDetails(
                        InstanceSourceDetailsInstanceSourceImageFilterDetailsArgs.builder()
                                .compartmentId(compartmentId)
                                .operatingSystem("Canonical Ubuntu")
                                .operatingSystemVersion("24.04")
                                .build())
                .build();
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }
}
