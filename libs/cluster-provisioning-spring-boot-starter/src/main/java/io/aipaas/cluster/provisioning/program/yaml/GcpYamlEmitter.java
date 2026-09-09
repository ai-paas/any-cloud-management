package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GCP VPC + Compute Engine 리소스를 YAML 로 생성. */
final class GcpYamlEmitter implements ProviderYamlEmitter {

    private static final String T_NETWORK = "gcp:compute/network:Network";
    private static final String T_SUBNETWORK = "gcp:compute/subnetwork:Subnetwork";
    private static final String T_FIREWALL = "gcp:compute/firewall:Firewall";
    private static final String T_ADDRESS = "gcp:compute/address:Address";
    private static final String T_INSTANCE = "gcp:compute/instance:Instance";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String DEFAULT_IMAGE = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64";

    @Override
    public String name() {
        return "gcp";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        requireConfig(gcp(spec).project(), "providerSpec.project");

        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        emitNetwork(b, spec);
        emitFirewall(b, spec);

        StandardOutputs.NodeRef master = emitNode(b, spec, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, "worker-" + i, workerUserData));
        }
        return new StandardOutputs.NodeRefs("sshKey", "net", "id", master, workers);
    }

    private void emitNetwork(PulumiProgram.Builder b, ClusterSpec spec) {
        String project = gcp(spec).project();
        b.resource(
                "net",
                T_NETWORK,
                Map.of(
                        "name",
                        spec.name() + "-net",
                        "project",
                        project,
                        // 자동 서브넷은 전 리전에 대역을 잡아 vpcCidr 과 충돌한다.
                        "autoCreateSubnetworks",
                        false));
        b.resource(
                "subnet",
                T_SUBNETWORK,
                Map.of(
                        "name", spec.name() + "-subnet",
                        "project", project,
                        "network", YamlRef.of("net", "id"),
                        "ipCidrRange", firstSubnet(spec),
                        "region", spec.region()));
    }

    private void emitFirewall(PulumiProgram.Builder b, ClusterSpec spec) {
        String project = gcp(spec).project();
        String tag = spec.name() + "-node";
        b.resource(
                "fw-external",
                T_FIREWALL,
                Map.of(
                        "name", spec.name() + "-fw-external",
                        "project", project,
                        "network", YamlRef.of("net", "id"),
                        "direction", "INGRESS",
                        "sourceRanges", List.of("0.0.0.0/0"),
                        "targetTags", List.of(tag),
                        "allows",
                                List.of(Map.of(
                                        "protocol",
                                        "tcp",
                                        "ports",
                                        List.of(
                                                String.valueOf(K8sConstants.PORT_SSH),
                                                String.valueOf(K8sConstants.PORT_KUBE_API_SERVER),
                                                K8sConstants.NODE_PORT_MIN + "-" + K8sConstants.NODE_PORT_MAX)))));
        b.resource(
                "fw-internal",
                T_FIREWALL,
                Map.of(
                        "name", spec.name() + "-fw-internal",
                        "project", project,
                        "network", YamlRef.of("net", "id"),
                        "direction", "INGRESS",
                        "sourceRanges", List.of(spec.vpcCidr()),
                        "targetTags", List.of(tag),
                        "allows",
                                List.of(
                                        Map.of("protocol", "tcp", "ports", List.of("1-65535")),
                                        Map.of("protocol", "udp", "ports", List.of("1-65535")),
                                        // Calico 기본값 ipipMode=Always 가 노드 간 파드 트래픽을 감싼다.
                                        Map.of("protocol", "ipip"))));
    }

    private StandardOutputs.NodeRef emitNode(PulumiProgram.Builder b, ClusterSpec spec, String node, String userData) {
        String project = gcp(spec).project();
        b.resource(
                "ip-" + node,
                T_ADDRESS,
                Map.of("name", spec.name() + "-" + node + "-ip", "project", project, "region", spec.region()));

        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("name", spec.name() + "-" + node);
        instance.put("project", project);
        instance.put("zone", spec.region() + "-a");
        instance.put("machineType", node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType());
        instance.put("tags", List.of(spec.name() + "-node"));
        instance.put("bootDisk", Map.of("initializeParams", Map.of("image", image(spec), "size", rootDiskGb(spec))));
        instance.put(
                "networkInterfaces",
                List.of(Map.of(
                        "subnetwork", YamlRef.of("subnet", "id"),
                        "accessConfigs", List.of(Map.of("natIp", YamlRef.of("ip-" + node, "address"))))));
        // GCP 는 cloud-init 을 user-data 키로 읽는다. startup-script 는 셸만 받아 kubeadm 흐름이 깨진다.
        instance.put(
                "metadata",
                Map.of(
                        "user-data",
                        userData,
                        "ssh-keys",
                        YamlRef.interpolate("%s:%s", sshUser(spec), YamlRef.of("sshKey", "publicKeyOpenssh"))));
        b.resource(node, T_INSTANCE, instance);

        return new StandardOutputs.NodeRef(
                node, "instanceId", "networkInterfaces[0].networkIp", "ip-" + node, "address");
    }

    private String image(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : DEFAULT_IMAGE;
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
    private static ProviderSpec.Gcp gcp(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Gcp g) return g;
        throw new IllegalStateException("Gcp 설정이 없다: provider=" + spec.provider());
    }
}
