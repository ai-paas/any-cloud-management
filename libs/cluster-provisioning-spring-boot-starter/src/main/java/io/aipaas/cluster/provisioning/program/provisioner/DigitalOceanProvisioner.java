package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import com.pulumi.digitalocean.Droplet;
import com.pulumi.digitalocean.DropletArgs;
import com.pulumi.digitalocean.Firewall;
import com.pulumi.digitalocean.FirewallArgs;
import com.pulumi.digitalocean.SshKey;
import com.pulumi.digitalocean.SshKeyArgs;
import com.pulumi.digitalocean.Vpc;
import com.pulumi.digitalocean.VpcArgs;
import com.pulumi.digitalocean.inputs.FirewallInboundRuleArgs;
import com.pulumi.digitalocean.inputs.FirewallOutboundRuleArgs;
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
 * DigitalOcean provider 구현. Go {@code infra/pulumi/pkg/providers/digitalocean/*} 등가물.
 *
 * <p>가장 단순한 CSP: VPC + Droplet (단일 객체로 public/private IP 포함) + Firewall (droplet-level).
 * Subnet 개념 없음, SecurityGroup 도 없음 — DO Firewall 이 droplet ID 직접 매칭.
 *
 * <p>주요 제약:
 *
 * <ul>
 *   <li>RootDiskSizeGb no-op — droplet 디스크는 size slug 에 종속.
 *   <li>Spot 미지원 — useSpot 은 no-op.
 *   <li>OS image 는 slug 형식 (예: ubuntu-24-04-x64).
 * </ul>
 */
public final class DigitalOceanProvisioner extends AbstractKubeadmProvisioner {

    private static final String DEFAULT_IMAGE = "ubuntu-24-04-x64";

    @Override
    public String name() {
        return "digitalocean";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        if (spec.region() == null || spec.region().isBlank()) {
            throw new IllegalStateException("region is required for DigitalOcean provisioning");
        }

        Vpc vpc = new Vpc(
                resourceName(spec, "vpc"),
                VpcArgs.builder()
                        .name(resourceName(spec, "vpc"))
                        .region(spec.region())
                        .ipRange(spec.vpcCidr())
                        .build());

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder()
                        .algorithm("RSA")
                        .rsaBits(4096)
                        .build());

        SshKey sshKey = new SshKey(
                resourceName(spec, "ssh-keypair"),
                SshKeyArgs.builder()
                        .name(resourceName(spec, "ssh-keypair"))
                        .publicKey(privateKey.publicKeyOpenssh())
                        .build());

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionDroplet(spec, vpc, sshKey, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException(
                    "DigitalOceanProvisioner: no master NodeSpec produced (masterCount="
                            + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionDroplet(spec, vpc, sshKey, node, masterInstance.resource()));
        }

        // Firewall.dropletIds 는 Integer list 필요 — Droplet.id 는 String, 변환.
        List<Output<Integer>> dropletIdInts = new ArrayList<>(1 + workerInstances.size());
        dropletIdInts.add(masterInstance.instanceId().applyValue(Integer::parseInt));
        for (InstanceOutput w : workerInstances) {
            dropletIdInts.add(w.instanceId().applyValue(Integer::parseInt));
        }
        Output<List<Integer>> allIds = Output.all(dropletIdInts);

        new Firewall(
                resourceName(spec, "fw"),
                FirewallArgs.builder()
                        .name(resourceName(spec, "fw"))
                        .dropletIds(allIds)
                        .inboundRules(List.of(
                                FirewallInboundRuleArgs.builder()
                                        .protocol("tcp")
                                        .portRange(String.valueOf(K8sConstants.PORT_SSH))
                                        .sourceAddresses("0.0.0.0/0", "::/0")
                                        .build(),
                                FirewallInboundRuleArgs.builder()
                                        .protocol("tcp")
                                        .portRange(String.valueOf(K8sConstants.PORT_KUBE_API_SERVER))
                                        .sourceAddresses("0.0.0.0/0", "::/0")
                                        .build(),
                                FirewallInboundRuleArgs.builder()
                                        .protocol("tcp")
                                        .portRange(K8sConstants.NODE_PORT_MIN + "-" + K8sConstants.NODE_PORT_MAX)
                                        .sourceAddresses("0.0.0.0/0", "::/0")
                                        .build(),
                                FirewallInboundRuleArgs.builder()
                                        .protocol("icmp")
                                        .sourceAddresses("0.0.0.0/0", "::/0")
                                        .build()))
                        .outboundRules(List.of(
                                FirewallOutboundRuleArgs.builder()
                                        .protocol("tcp")
                                        .portRange("1-65535")
                                        .destinationAddresses("0.0.0.0/0", "::/0")
                                        .build(),
                                FirewallOutboundRuleArgs.builder()
                                        .protocol("udp")
                                        .portRange("1-65535")
                                        .destinationAddresses("0.0.0.0/0", "::/0")
                                        .build(),
                                FirewallOutboundRuleArgs.builder()
                                        .protocol("icmp")
                                        .destinationAddresses("0.0.0.0/0", "::/0")
                                        .build()))
                        .build());

        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, vpc.id(), Map.of());
    }

    private InstanceOutput provisionDroplet(
            ClusterSpec spec, Vpc vpc, SshKey sshKey, NodeSpec node, Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);
        String image = (node.osImage() != null && !node.osImage().isBlank()) ? node.osImage() : DEFAULT_IMAGE;
        String roleTag = node.role() == InstanceRole.MASTER ? "k8s-master" : "k8s-worker";

        // DO ssh_keys 는 SSH key ID (int 또는 fingerprint string). Pulumi Java SDK 의 sshKeys 는 List<String> —
        // Account SshKey 의 id 는 String 형식 Output (DO 가 fingerprint 도 허용하므로 호환). 단, 명시
        // fingerprint 사용도 가능하지만 신규 SshKey 의 id 가 가장 안전.
        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Droplet droplet = new Droplet(
                resourceName(spec, suffix),
                DropletArgs.builder()
                        .name(resourceName(spec, suffix))
                        .region(spec.region())
                        .size(node.instanceType())
                        .image(image)
                        .monitoring(false)
                        .ipv6(false)
                        .vpcUuid(vpc.id())
                        .sshKeys(sshKey.id().applyValue(List::of))
                        .userData(node.userData())
                        .tags(List.of(spec.name(), spec.environment(), roleTag))
                        .build(),
                opts);

        return new InstanceOutput(
                droplet, droplet.id(), droplet.ipv4AddressPrivate(), droplet.ipv4Address());
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }
}
