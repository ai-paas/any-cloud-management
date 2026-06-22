package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.alicloud.AlicloudFunctions;
import com.pulumi.alicloud.ecs.EcsFunctions;
import com.pulumi.alicloud.ecs.Instance;
import com.pulumi.alicloud.ecs.InstanceArgs;
import com.pulumi.alicloud.ecs.KeyPair;
import com.pulumi.alicloud.ecs.KeyPairArgs;
import com.pulumi.alicloud.ecs.SecurityGroup;
import com.pulumi.alicloud.ecs.SecurityGroupArgs;
import com.pulumi.alicloud.ecs.SecurityGroupRule;
import com.pulumi.alicloud.ecs.SecurityGroupRuleArgs;
import com.pulumi.alicloud.ecs.inputs.GetImagesArgs;
import com.pulumi.alicloud.inputs.GetZonesArgs;
import com.pulumi.alicloud.ram.Role;
import com.pulumi.alicloud.ram.RoleArgs;
import com.pulumi.alicloud.ram.RolePolicyAttachment;
import com.pulumi.alicloud.ram.RolePolicyAttachmentArgs;
import com.pulumi.alicloud.vpc.Network;
import com.pulumi.alicloud.vpc.NetworkArgs;
import com.pulumi.alicloud.vpc.Switch;
import com.pulumi.alicloud.vpc.SwitchArgs;
import com.pulumi.core.Output;
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
 * Alibaba Cloud provider. Go {@code infra/pulumi/pkg/providers/alibaba/*} 등가물.
 *
 * <p>VPC + VSwitch (단일 zone) + ECS SecurityGroup + 3 rules + RAM Role + ECS Instance.
 * Spot 매핑: SpotStrategy=SpotAsPriceGo + SpotDuration=0.
 */
public final class AlibabaProvisioner extends AbstractKubeadmProvisioner {

    private static final String ASSUME_ROLE_POLICY =
            """
            {
              "Statement": [
                {
                  "Action": "sts:AssumeRole",
                  "Effect": "Allow",
                  "Principal": {"Service": ["ecs.aliyuncs.com"]}
                }
              ],
              "Version": "1"
            }""";

    @Override
    public String name() {
        return "alibaba";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        NetworkResult net = provisionNetwork(spec);
        Output<String> imageId = resolveImage(spec);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder().algorithm("RSA").rsaBits(4096).build());

        KeyPair keyPair = new KeyPair(
                resourceName(spec, "keypair"),
                KeyPairArgs.builder()
                        .keyName(resourceName(spec, "keypair"))
                        .publicKey(privateKey.publicKeyOpenssh())
                        .build());

        Role ramRole = new Role(
                resourceName(spec, "ccm-role"),
                RoleArgs.builder()
                        .name(resourceName(spec, "ccm-role"))
                        .description("Anycloud K8s cloud-controller-manager role")
                        .document(ASSUME_ROLE_POLICY)
                        .force(true)
                        .build());

        for (String policyName : List.of("AliyunECSFullAccess", "AliyunVPCFullAccess", "AliyunSLBFullAccess")) {
            new RolePolicyAttachment(
                    resourceName(spec, "ccm-role-" + policyName),
                    RolePolicyAttachmentArgs.builder()
                            .policyName(policyName)
                            .policyType("System")
                            .roleName(ramRole.name())
                            .build());
        }

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionInstance(spec, net, imageId, keyPair, ramRole, node, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException(
                    "AlibabaProvisioner: no master NodeSpec produced (masterCount=" + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionInstance(spec, net, imageId, keyPair, ramRole, node, masterInstance.resource()));
        }

        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.vpc.id(), Map.of());
    }

    // ===== Network =====

    private record NetworkResult(Network vpc, Switch vswitch, SecurityGroup sg) {}

    private NetworkResult provisionNetwork(ClusterSpec spec) {
        Network vpc = new Network(
                resourceName(spec, "vpc"),
                NetworkArgs.builder()
                        .vpcName(resourceName(spec, "vpc"))
                        .cidrBlock(spec.vpcCidr())
                        .build());

        // CSP API 응답이 비어있거나 (rare) 또는 mock 환경에서 null 일 수 있으므로 region+"-a" fallback.
        // 실 운영에서 zones() == null 은 region 미지원/credential 오류 시그널 — 진단은 Pulumi 가 instance 생성
        // 단계의 명확한 에러로 노출.
        Output<String> zone = AlicloudFunctions.getZones(GetZonesArgs.builder().build())
                .applyValue(r -> {
                    var zones = r.zones();
                    if (zones == null || zones.isEmpty()) return spec.region() + "-a";
                    return zones.get(0).id();
                });

        Switch vswitch = new Switch(
                resourceName(spec, "vsw"),
                SwitchArgs.builder()
                        .vpcId(vpc.id())
                        .zoneId(zone)
                        .cidrBlock(spec.subnetCidrs().get(0))
                        .vswitchName(resourceName(spec, "vsw"))
                        .build());

        SecurityGroup sg = new SecurityGroup(
                resourceName(spec, "sg"),
                SecurityGroupArgs.builder()
                        .name(resourceName(spec, "sg"))
                        .vpcId(vpc.id())
                        .description("anycloud kubernetes security group")
                        .build());

        // 3 ingress rules: SSH / kube-apiserver / NodePort range.
        int idx = 1;
        for (String port : List.of(
                K8sConstants.PORT_SSH + "/" + K8sConstants.PORT_SSH,
                K8sConstants.PORT_KUBE_API_SERVER + "/" + K8sConstants.PORT_KUBE_API_SERVER,
                K8sConstants.NODE_PORT_MIN + "/" + K8sConstants.NODE_PORT_MAX)) {
            new SecurityGroupRule(
                    resourceName(spec, "sg-rule-" + idx),
                    SecurityGroupRuleArgs.builder()
                            .type("ingress")
                            .ipProtocol("tcp")
                            .portRange(port)
                            .cidrIp("0.0.0.0/0")
                            .securityGroupId(sg.id())
                            .build());
            idx++;
        }
        return new NetworkResult(vpc, vswitch, sg);
    }

    // ===== Image =====

    private Output<String> resolveImage(ClusterSpec spec) {
        if (spec.osImage() != null && !spec.osImage().isBlank()) {
            return Output.of(spec.osImage());
        }
        return EcsFunctions.getImages(GetImagesArgs.builder()
                        .nameRegex("ubuntu_24_04_x64.*")
                        .owners("system")
                        .mostRecent(true)
                        .build())
                .applyValue(r -> r.images().get(0).id());
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec,
            NetworkResult net,
            Output<String> imageId,
            KeyPair keyPair,
            Role ramRole,
            NodeSpec node,
            Resource dependsOn) {
        String suffix = node.role().token() + "-" + (node.index() + 1);

        InstanceArgs.Builder argsBuilder = InstanceArgs.builder()
                .instanceName(resourceName(spec, suffix))
                .instanceType(node.instanceType())
                .imageId(node.osImage() != null && !node.osImage().isBlank() ? Output.of(node.osImage()) : imageId)
                .vswitchId(net.vswitch.id())
                .securityGroups(net.sg.id().applyValue(List::of))
                .keyName(keyPair.keyName())
                .roleName(ramRole.name())
                .systemDiskCategory("cloud_essd")
                .systemDiskSize(node.rootDiskSizeGb())
                .internetMaxBandwidthOut(10)
                .userData(node.userData());

        if (node.useSpot()) {
            argsBuilder.spotStrategy("SpotAsPriceGo").spotDuration(0);
        }

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Instance instance = new Instance(resourceName(spec, suffix), argsBuilder.build(), opts);
        return new InstanceOutput(instance, instance.id(), instance.privateIp(), instance.publicIp());
    }

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }
}
