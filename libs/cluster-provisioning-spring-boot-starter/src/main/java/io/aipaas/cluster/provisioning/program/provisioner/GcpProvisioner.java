package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import com.pulumi.gcp.compute.Address;
import com.pulumi.gcp.compute.AddressArgs;
import com.pulumi.gcp.compute.ComputeFunctions;
import com.pulumi.gcp.compute.Firewall;
import com.pulumi.gcp.compute.FirewallArgs;
import com.pulumi.gcp.compute.Instance;
import com.pulumi.gcp.compute.InstanceArgs;
import com.pulumi.gcp.compute.Network;
import com.pulumi.gcp.compute.NetworkArgs;
import com.pulumi.gcp.compute.Subnetwork;
import com.pulumi.gcp.compute.SubnetworkArgs;
import com.pulumi.gcp.compute.inputs.FirewallAllowArgs;
import com.pulumi.gcp.compute.inputs.GetImageArgs;
import com.pulumi.gcp.compute.inputs.GetZonesArgs;
import com.pulumi.gcp.compute.inputs.InstanceBootDiskArgs;
import com.pulumi.gcp.compute.inputs.InstanceBootDiskInitializeParamsArgs;
import com.pulumi.gcp.compute.inputs.InstanceNetworkInterfaceAccessConfigArgs;
import com.pulumi.gcp.compute.inputs.InstanceNetworkInterfaceArgs;
import com.pulumi.gcp.compute.inputs.InstanceSchedulingArgs;
import com.pulumi.gcp.serviceaccount.Account;
import com.pulumi.gcp.serviceaccount.AccountArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import com.pulumi.tls.PrivateKey;
import com.pulumi.tls.PrivateKeyArgs;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.ResourceNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GCP provider 의 Java 구현. Go {@code infra/pulumi/pkg/providers/gcp/*} 등가물.
 *
 * <p>VPC + regional subnet + external/internal firewall + per-instance static IP + ServiceAccount +
 * Compute Instance. AWS 와 차이:
 *
 * <ul>
 *   <li>SecurityGroup 대신 NetworkTag — Firewall.TargetTags 와 Instance.Tags 매칭.
 *   <li>Spot 매핑: Scheduling.preemptible=true + automaticRestart=false.
 *   <li>Subnet 1개 (regional) — instance 는 zone round-robin 으로 분산.
 *   <li>Public IP 는 별도 Address 자원 + AccessConfig.natIp 로 attach.
 * </ul>
 */
public final class GcpProvisioner extends AbstractKubeadmProvisioner {

    @Override
    public String name() {
        return "gcp";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        if (spec.gcpProject() == null || spec.gcpProject().isBlank()) {
            throw new IllegalStateException("gcpProject is required for GCP provisioning");
        }
        if (spec.region() == null || spec.region().isBlank()) {
            throw new IllegalStateException("region is required for GCP provisioning");
        }

        Output<List<String>> zoneNames = ComputeFunctions.getZones(GetZonesArgs.builder()
                        .region(spec.region())
                        .status("UP")
                        .build())
                .applyValue(r -> r.names());

        NetworkResult net = provisionNetwork(spec);
        Output<String> imageSelfLink = resolveImage(spec);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder()
                        .algorithm("RSA")
                        .rsaBits(4096)
                        .build());

        Account sa = new Account(
                resourceName(spec, "sa"),
                AccountArgs.builder()
                        .accountId(trimAccountId(resourceName(spec, "sa")))
                        .displayName(spec.name() + " vm cluster service account")
                        .project(spec.gcpProject())
                        .build());

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance =
                    provisionInstance(spec, net, zoneNames, imageSelfLink, sa, privateKey, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException(
                    "GcpProvisioner: no master NodeSpec produced (masterCount=" + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionInstance(
                    spec, net, zoneNames, imageSelfLink, sa, privateKey, node, masterInstance.resource()));
        }

        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.network.id(), Map.of());
    }

    // ===== Network =====

    private record NetworkResult(Network network, Subnetwork subnet, String networkTag) {}

    private NetworkResult provisionNetwork(ClusterSpec spec) {
        Network network = new Network(
                resourceName(spec, "vpc"),
                NetworkArgs.builder()
                        .project(spec.gcpProject())
                        .autoCreateSubnetworks(false)
                        .routingMode("REGIONAL")
                        .build());

        Subnetwork subnet = new Subnetwork(
                resourceName(spec, "subnet"),
                SubnetworkArgs.builder()
                        .project(spec.gcpProject())
                        .region(spec.region())
                        .ipCidrRange(spec.subnetCidrs().get(0))
                        .network(network.id())
                        .privateIpGoogleAccess(true)
                        .build());

        // tag 길이 제한 — 63자, hyphen 끝 금지. ResourceNames.sanitize 가 이미 보장하지만 추가 sanitize.
        String networkTag = ResourceNames.sanitize(spec.name() + "-k8s");

        // External firewall — SSH / kube-apiserver / NodePort / VXLAN / Flannel / ICMP.
        new Firewall(
                resourceName(spec, "fw"),
                FirewallArgs.builder()
                        .project(spec.gcpProject())
                        .network(network.name())
                        .allows(List.of(
                                FirewallAllowArgs.builder()
                                        .protocol("tcp")
                                        .ports("22", "6443", "30000-32767")
                                        .build(),
                                FirewallAllowArgs.builder()
                                        .protocol("udp")
                                        .ports("8472", "4789")
                                        .build(),
                                FirewallAllowArgs.builder().protocol("icmp").build()))
                        .sourceRanges("0.0.0.0/0")
                        .targetTags(networkTag)
                        .build());

        // Internal firewall — VPC CIDR 내 모든 트래픽 (pod-to-pod / etcd / kubelet).
        new Firewall(
                resourceName(spec, "fw-internal"),
                FirewallArgs.builder()
                        .project(spec.gcpProject())
                        .network(network.name())
                        .allows(List.of(
                                FirewallAllowArgs.builder().protocol("tcp").build(),
                                FirewallAllowArgs.builder().protocol("udp").build(),
                                FirewallAllowArgs.builder().protocol("icmp").build()))
                        .sourceRanges(spec.vpcCidr())
                        .targetTags(networkTag)
                        .build());

        return new NetworkResult(network, subnet, networkTag);
    }

    // ===== Image =====

    private Output<String> resolveImage(ClusterSpec spec) {
        if (spec.osImage() != null && !spec.osImage().isBlank()) {
            return Output.of(spec.osImage());
        }
        // Canonical Ubuntu 24.04 LTS — public image family.
        return ComputeFunctions.getImage(GetImageArgs.builder()
                        .project("ubuntu-os-cloud")
                        .family("ubuntu-2404-lts-amd64")
                        .build())
                .applyValue(r -> r.selfLink());
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec,
            NetworkResult net,
            Output<List<String>> zoneNames,
            Output<String> imageSelfLink,
            Account sa,
            PrivateKey privateKey,
            NodeSpec node,
            Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);

        // Per-instance static external IP.
        Address address = new Address(
                resourceName(spec, suffix + "-ip"),
                AddressArgs.builder()
                        .project(spec.gcpProject())
                        .region(spec.region())
                        .build());

        // Zone selection — NodeSpec.index round-robin over active zones.
        final int nodeIndex = node.index();
        Output<String> zone = zoneNames.applyValue(zones -> zones.get(nodeIndex % zones.size()));

        // SSH key metadata — user:public-key 형식. cloud-init 이 worker 부팅 시 master 의 ssh key 도 받음.
        Output<String> sshKeyMetadata =
                privateKey.publicKeyOpenssh().applyValue(pub -> spec.sshUser() + ":" + pub);
        Output<Map<String, String>> metadata = Output.tuple(sshKeyMetadata, node.userData())
                .applyValue(t -> Map.of(
                        "ssh-keys", t.t1,
                        "startup-script", t.t2));

        InstanceArgs.Builder argsBuilder = InstanceArgs.builder()
                .project(spec.gcpProject())
                .zone(zone)
                .machineType(node.instanceType())
                .tags(net.networkTag)
                .bootDisk(InstanceBootDiskArgs.builder()
                        .initializeParams(InstanceBootDiskInitializeParamsArgs.builder()
                                .image(imageSelfLink)
                                .size(node.rootDiskSizeGb())
                                .build())
                        .build())
                .networkInterfaces(InstanceNetworkInterfaceArgs.builder()
                        .subnetwork(net.subnet.id())
                        .accessConfigs(InstanceNetworkInterfaceAccessConfigArgs.builder()
                                .natIp(address.address())
                                .build())
                        .build())
                .metadata(metadata)
                .serviceAccount(com.pulumi.gcp.compute.inputs.InstanceServiceAccountArgs.builder()
                        .email(sa.email())
                        .scopes("cloud-platform")
                        .build());

        if (node.useSpot()) {
            // GCP preemptible — AWS spot 와 의미 동일 (capacity reclaimed any time).
            argsBuilder.scheduling(InstanceSchedulingArgs.builder()
                    .preemptible(true)
                    .automaticRestart(false)
                    .build());
        }

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Instance instance = new Instance(resourceName(spec, suffix), argsBuilder.build(), opts);

        // GCP private IP 는 networkInterface[0].networkIp, public IP 는 address.address.
        Output<String> privateIp =
                instance.networkInterfaces().applyValue(nics -> nics.get(0).networkIp().orElse(""));
        return new InstanceOutput(instance, instance.id(), privateIp, address.address());
    }

    // ===== Naming =====

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }

    /**
     * GCP service account ID 규칙 — 6-30자, [a-z][-a-z0-9]*[a-z0-9]. ResourceNames.sanitize 결과를
     * 30자로 자르고 끝 hyphen 제거.
     */
    private static String trimAccountId(String raw) {
        String s = raw;
        if (s.length() > 30) s = s.substring(0, 30);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
