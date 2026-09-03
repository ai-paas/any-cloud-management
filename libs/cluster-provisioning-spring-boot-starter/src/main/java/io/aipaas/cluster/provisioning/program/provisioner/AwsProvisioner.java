package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.Ec2Functions;
import com.pulumi.aws.ec2.Instance;
import com.pulumi.aws.ec2.InstanceArgs;
import com.pulumi.aws.ec2.InternetGateway;
import com.pulumi.aws.ec2.InternetGatewayArgs;
import com.pulumi.aws.ec2.KeyPair;
import com.pulumi.aws.ec2.KeyPairArgs;
import com.pulumi.aws.ec2.RouteTable;
import com.pulumi.aws.ec2.RouteTableArgs;
import com.pulumi.aws.ec2.RouteTableAssociation;
import com.pulumi.aws.ec2.RouteTableAssociationArgs;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.Subnet;
import com.pulumi.aws.ec2.SubnetArgs;
import com.pulumi.aws.ec2.Vpc;
import com.pulumi.aws.ec2.VpcArgs;
import com.pulumi.aws.ec2.inputs.GetAmiArgs;
import com.pulumi.aws.ec2.inputs.GetAmiFilterArgs;
import com.pulumi.aws.ec2.inputs.InstanceInstanceMarketOptionsArgs;
import com.pulumi.aws.ec2.inputs.InstanceInstanceMarketOptionsSpotOptionsArgs;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.iam.InstanceProfile;
import com.pulumi.aws.iam.InstanceProfileArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.RolePolicyAttachment;
import com.pulumi.aws.iam.RolePolicyAttachmentArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import com.pulumi.tls.PrivateKey;
import com.pulumi.tls.PrivateKeyArgs;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.ResourceNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS provider 구현. VPC + multi-AZ subnet + IGW + RT + SG + EC2 (master + workers) +
 * TLS keypair + IAM role + optional RDS postgres.
 *
 * <p>HA 한계: masterCount=1 가정. 본격 HA 는 VIP/LB 도입 필요.
 */
public final class AwsProvisioner extends AbstractKubeadmProvisioner {

    @Override
    public String name() {
        return "aws";
    }

    @Override
    protected ProvisionedCluster provisionResources(Context ctx, ClusterSpec spec) {
        NetworkResult net = provisionNetwork(spec);

        PrivateKey privateKey = new PrivateKey(
                resourceName(spec, "ssh-key"),
                PrivateKeyArgs.builder()
                        .algorithm("RSA")
                        .rsaBits(4096)
                        .build());
        KeyPair keyPair = new KeyPair(
                resourceName(spec, "keypair"),
                KeyPairArgs.builder()
                        .keyName(resourceName(spec, "keypair"))
                        .publicKey(privateKey.publicKeyOpenssh())
                        .tags(tags(spec, "keypair"))
                        .build());
        InstanceProfile instanceProfile = buildInstanceProfile(spec);

        List<NodeSpec> nodes = NodeSpecs.from(spec);
        InstanceOutput masterInstance = null;
        List<InstanceOutput> workerInstances = new ArrayList<>(spec.workerCount());

        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.MASTER) continue;
            masterInstance = provisionInstance(spec, net, node, keyPair, instanceProfile, null);
        }
        if (masterInstance == null) {
            throw new IllegalStateException("AwsProvisioner: no master NodeSpec produced (masterCount="
                    + spec.masterCount() + ")");
        }
        for (NodeSpec node : nodes) {
            if (node.role() != InstanceRole.WORKER) continue;
            workerInstances.add(provisionInstance(
                    spec, net, node, keyPair, instanceProfile, masterInstance.resource()));
        }

        Map<String, Output<?>> extras = new LinkedHashMap<>();
        if (spec.database() != null && spec.database().enabled()) {
            extras.putAll(provisionDatabase(spec, net));
        }
        return new ProvisionedCluster(privateKey, masterInstance, workerInstances, net.vpcId, extras);
    }

    // ===== Network =====

    private record NetworkResult(
            Vpc vpc,
            List<Subnet> subnets,
            SecurityGroup securityGroup,
            Output<String> vpcId,
            List<Output<String>> subnetIds,
            Output<String> securityGroupId) {}

    private NetworkResult provisionNetwork(ClusterSpec spec) {
        // AZ list — 최소 2 zone 필요 (multi-AZ subnet 배치).
        Output<List<String>> zoneNames =
                AwsFunctions.getAvailabilityZones().applyValue(r -> r.names());

        Vpc vpc = new Vpc(
                resourceName(spec, "vpc"),
                VpcArgs.builder()
                        .cidrBlock(spec.vpcCidr())
                        .enableDnsHostnames(true)
                        .enableDnsSupport(true)
                        .tags(tags(spec, "vpc"))
                        .build());

        InternetGateway igw = new InternetGateway(
                resourceName(spec, "igw"),
                InternetGatewayArgs.builder()
                        .vpcId(vpc.id())
                        .tags(tags(spec, "igw"))
                        .build());

        RouteTable rt = new RouteTable(
                resourceName(spec, "rt"),
                RouteTableArgs.builder()
                        .vpcId(vpc.id())
                        .routes(RouteTableRouteArgs.builder()
                                .cidrBlock("0.0.0.0/0")
                                .gatewayId(igw.id())
                                .build())
                        .tags(tags(spec, "rt"))
                        .build());

        List<Subnet> subnets = new ArrayList<>(spec.subnetCidrs().size());
        List<Output<String>> subnetIds = new ArrayList<>(spec.subnetCidrs().size());
        for (int i = 0; i < spec.subnetCidrs().size(); i++) {
            final int idx = i;
            String cidr = spec.subnetCidrs().get(i);
            // AZ assignment — round-robin over getAvailabilityZones result.
            Output<String> az = zoneNames.applyValue(names -> names.get(idx % names.size()));
            Subnet subnet = new Subnet(
                    resourceName(spec, "subnet-" + (i + 1)),
                    SubnetArgs.builder()
                            .vpcId(vpc.id())
                            .cidrBlock(cidr)
                            .availabilityZone(az)
                            .mapPublicIpOnLaunch(true)
                            .tags(tags(spec, "subnet-" + (i + 1)))
                            .build());
            new RouteTableAssociation(
                    resourceName(spec, "rta-" + (i + 1)),
                    RouteTableAssociationArgs.builder()
                            .routeTableId(rt.id())
                            .subnetId(subnet.id())
                            .build());
            subnets.add(subnet);
            subnetIds.add(subnet.id());
        }

        SecurityGroup sg = new SecurityGroup(
                resourceName(spec, "nodes-sg"),
                SecurityGroupArgs.builder()
                        .vpcId(vpc.id())
                        .description("anycloud kubernetes node security group")
                        .ingress(List.of(
                                SecurityGroupIngressArgs.builder()
                                        .protocol("tcp")
                                        .fromPort(K8sConstants.PORT_SSH)
                                        .toPort(K8sConstants.PORT_SSH)
                                        .cidrBlocks("0.0.0.0/0")
                                        .description("ssh")
                                        .build(),
                                SecurityGroupIngressArgs.builder()
                                        .protocol("tcp")
                                        .fromPort(K8sConstants.PORT_KUBE_API_SERVER)
                                        .toPort(K8sConstants.PORT_KUBE_API_SERVER)
                                        .cidrBlocks("0.0.0.0/0")
                                        .description("kubernetes api")
                                        .build(),
                                SecurityGroupIngressArgs.builder()
                                        .protocol("-1")
                                        .fromPort(0)
                                        .toPort(0)
                                        .self(true)
                                        .description("allow all node to node traffic")
                                        .build(),
                                SecurityGroupIngressArgs.builder()
                                        .protocol("tcp")
                                        .fromPort(K8sConstants.NODE_PORT_MIN)
                                        .toPort(K8sConstants.NODE_PORT_MAX)
                                        .cidrBlocks("0.0.0.0/0")
                                        .description("nodeport range")
                                        .build()))
                        .egress(SecurityGroupEgressArgs.builder()
                                .protocol("-1")
                                .fromPort(0)
                                .toPort(0)
                                .cidrBlocks("0.0.0.0/0")
                                .description("all outbound")
                                .build())
                        .tags(tags(spec, "nodes-sg"))
                        .build());

        return new NetworkResult(vpc, subnets, sg, vpc.id(), subnetIds, sg.id());
    }

    // ===== AMI =====

    private Output<String> resolveAmi(ClusterSpec spec, String instanceType) {
        if (spec.osImage() != null && !spec.osImage().isBlank()) {
            return Output.of(spec.osImage());
        }
        boolean arm = isArm64Family(instanceType);
        String archFilter = arm ? "arm64" : "x86_64";
        String namePattern = arm
                ? "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*"
                : "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*";
        // Canonical Ubuntu 24.04 LTS owner (Canonical, Inc.).
        return Ec2Functions.getAmi(GetAmiArgs.builder()
                        .mostRecent(true)
                        .owners("099720109477")
                        .filters(List.of(
                                GetAmiFilterArgs.builder()
                                        .name("name")
                                        .values(namePattern)
                                        .build(),
                                GetAmiFilterArgs.builder()
                                        .name("virtualization-type")
                                        .values("hvm")
                                        .build(),
                                GetAmiFilterArgs.builder()
                                        .name("architecture")
                                        .values(archFilter)
                                        .build()))
                        .build())
                .applyValue(r -> r.id());
    }

    /**
     * AWS Graviton (ARM64) instance type 인지 판정. instance type 의 family token (`.` 앞부분) 이
     * {@code a<digit>} (a1) 또는 {@code [a-z]+<digit>+g[a-z]*} (t4g, m6g, c7gn, r8gd 등) 매치하면 ARM.
     * Mismatch 시 EC2 RunInstances 가 architecture 400 에러를 반환하므로 AMI 선택 시 필수.
     */
    private static boolean isArm64Family(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) return false;
        int dot = instanceType.indexOf('.');
        String family = dot > 0 ? instanceType.substring(0, dot) : instanceType;
        return family.matches("a\\d") || family.matches("[a-z]+\\d+g[a-z]*");
    }

    // ===== IAM =====

    private static final String ASSUME_ROLE_POLICY =
            """
            {
              "Version": "2012-10-17",
              "Statement": [{
                "Action": "sts:AssumeRole",
                "Principal": { "Service": "ec2.amazonaws.com" },
                "Effect": "Allow"
              }]
            }""";

    private InstanceProfile buildInstanceProfile(ClusterSpec spec) {
        Role role = new Role(
                resourceName(spec, "ec2-role"),
                RoleArgs.builder()
                        .assumeRolePolicy(ASSUME_ROLE_POLICY)
                        .tags(tags(spec, "ec2-role"))
                        .build());

        List<String> attached = List.of(
                "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
                "arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess");
        for (int i = 0; i < attached.size(); i++) {
            new RolePolicyAttachment(
                    resourceName(spec, "role-attach-" + (i + 1)),
                    RolePolicyAttachmentArgs.builder()
                            .role(role.name())
                            .policyArn(attached.get(i))
                            .build());
        }

        return new InstanceProfile(
                resourceName(spec, "ec2-profile"),
                InstanceProfileArgs.builder()
                        .role(role.name())
                        .tags(tags(spec, "ec2-profile"))
                        .build());
    }

    // ===== Instance =====

    private InstanceOutput provisionInstance(
            ClusterSpec spec,
            NetworkResult net,
            NodeSpec node,
            KeyPair keyPair,
            InstanceProfile instanceProfile,
            Resource dependsOn) {
        if (node.subnetIndex() < 0 || node.subnetIndex() >= net.subnetIds.size()) {
            throw new IllegalStateException("subnetIndex " + node.subnetIndex()
                    + " out of range (have " + net.subnetIds.size() + " subnets)");
        }
        String tagSuffix = node.role().token() + "-" + (node.index() + 1);

        InstanceArgs.Builder argsBuilder = InstanceArgs.builder()
                .ami(resolveAmi(spec, node.instanceType()))
                .instanceType(node.instanceType())
                .subnetId(net.subnetIds.get(node.subnetIndex()))
                .vpcSecurityGroupIds(net.securityGroupId.applyValue(List::of))
                .iamInstanceProfile(instanceProfile.name())
                .keyName(keyPair.keyName())
                .associatePublicIpAddress(true)
                .userData(node.userData())
                .tags(tags(spec, tagSuffix));

        if (node.useSpot()) {
            argsBuilder.instanceMarketOptions(InstanceInstanceMarketOptionsArgs.builder()
                    .marketType("spot")
                    .spotOptions(InstanceInstanceMarketOptionsSpotOptionsArgs.builder()
                            .spotInstanceType("one-time")
                            .instanceInterruptionBehavior("terminate")
                            .build())
                    .build());
        }
        if (node.rootDiskSizeGb() > 0) {
            argsBuilder.rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                    .volumeSize(node.rootDiskSizeGb())
                    .volumeType("gp3")
                    .deleteOnTermination(true)
                    .build());
        }

        CustomResourceOptions opts = dependsOn == null
                ? CustomResourceOptions.Empty
                : CustomResourceOptions.builder().dependsOn(dependsOn).build();

        Instance instance = new Instance(resourceName(spec, tagSuffix), argsBuilder.build(), opts);
        return new InstanceOutput(instance, instance.id(), instance.privateIp(), instance.publicIp());
    }

    // ===== RDS =====

    private Map<String, Output<?>> provisionDatabase(ClusterSpec spec, NetworkResult net) {
        SecurityGroup dbSg = new SecurityGroup(
                resourceName(spec, "db-sg"),
                SecurityGroupArgs.builder()
                        .vpcId(net.vpc.id())
                        .description("postgresql security group")
                        .ingress(SecurityGroupIngressArgs.builder()
                                .protocol("tcp")
                                .fromPort(5432)
                                .toPort(5432)
                                .securityGroups(net.securityGroupId.applyValue(List::of))
                                .description("allow postgres from k8s nodes")
                                .build())
                        .egress(SecurityGroupEgressArgs.builder()
                                .protocol("-1")
                                .fromPort(0)
                                .toPort(0)
                                .cidrBlocks("0.0.0.0/0")
                                .description("all outbound")
                                .build())
                        .tags(tags(spec, "db-sg"))
                        .build());

        List<Output<String>> subnetIdOutputs = net.subnetIds;
        Output<List<String>> subnetIdsFlat = Output.all(subnetIdOutputs);

        SubnetGroup subnetGroup = new SubnetGroup(
                resourceName(spec, "db-subnets"),
                SubnetGroupArgs.builder()
                        .subnetIds(subnetIdsFlat)
                        .tags(tags(spec, "db-subnets"))
                        .build());

        com.pulumi.aws.rds.Instance instance = new com.pulumi.aws.rds.Instance(
                resourceName(spec, "postgres"),
                com.pulumi.aws.rds.InstanceArgs.builder()
                        .allocatedStorage(spec.database().allocatedStorageGb())
                        .applyImmediately(true)
                        .dbName(spec.database().name())
                        .engine("postgres")
                        .engineVersion("16.4")
                        .instanceClass(spec.database().instanceClass())
                        .username(spec.database().username())
                        .password(spec.database().password())
                        .dbSubnetGroupName(subnetGroup.name())
                        .vpcSecurityGroupIds(dbSg.id().applyValue(List::of))
                        .publiclyAccessible(spec.database().publiclyAccessible())
                        .skipFinalSnapshot(true)
                        .deletionProtection(false)
                        .storageEncrypted(true)
                        .tags(tags(spec, "postgres"))
                        .build());

        Map<String, Output<?>> outputs = new LinkedHashMap<>();
        outputs.put(
                "dbEndpoint",
                Output.tuple(instance.address(), instance.port())
                        .applyValue(t -> t.t1 + ":" + t.t2));
        outputs.put("dbName", Output.of(spec.database().name()));
        outputs.put("dbUsername", Output.of(spec.database().username()));
        return outputs;
    }

    // ===== Naming + tags =====

    private static String resourceName(ClusterSpec spec, String suffix) {
        return ResourceNames.join(spec.name(), suffix);
    }

    private static Map<String, String> tags(ClusterSpec spec, String component) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Name", resourceName(spec, component));
        m.put("Project", "anycloud");
        m.put("Environment", spec.environment());
        m.put("Cluster", spec.name());
        m.put("ManagedBy", "pulumi");
        m.put("Component", component);
        return m;
    }
}
