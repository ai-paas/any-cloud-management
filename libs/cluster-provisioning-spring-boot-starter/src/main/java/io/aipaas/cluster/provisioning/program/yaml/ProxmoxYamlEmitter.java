package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxmox VE 노드 위에 VM 을 올린다.
 *
 * <p>다른 CSP 와 달리 VPC, 서브넷, 보안그룹을 만들지 않는다. Proxmox 는 하이퍼바이저라 네트워크가
 * 이미 존재하는 브리지이고, 방화벽은 노드 단위로 운영자가 관리한다. 그래서 vpcCidr 과 subnetCidrs 는
 * 이 emitter 에서 쓰이지 않는다.
 */
final class ProxmoxYamlEmitter implements ProviderYamlEmitter {

    private static final String T_DOWNLOAD_FILE = "proxmoxve:download/file:File";
    private static final String T_FILE = "proxmoxve:index/fileLegacy:FileLegacy";
    private static final String T_VM = "proxmoxve:index/vmLegacy:VmLegacy";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String DEFAULT_IMAGE_URL =
            "https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-amd64.img";

    private static final String ROOT_DISK_INTERFACE = "scsi0";

    /** cloud-init 디스크는 IDE 에만 붙는다. scsi0 는 루트 디스크가 쓴다. */
    private static final String CLOUD_INIT_INTERFACE = "ide2";

    private static final int DEFAULT_CORES = 2;
    private static final int DEFAULT_MEMORY_MIB = 4096;

    @Override
    public String name() {
        return "proxmox";
    }

    @Override
    public StandardOutputs.NodeRefs emit(PulumiProgram.Builder b, ClusterSpec spec) {
        ProviderSpec.Proxmox px = proxmox(spec);
        requireConfig(px.nodeName(), "providerSpec.nodeName");

        b.resource("sshKey", T_PRIVATE_KEY, Map.of("algorithm", "RSA", "rsaBits", 4096));
        emitImage(b, spec, px);

        StandardOutputs.NodeRef master = emitNode(b, spec, px, "master", KubeadmUserData.master(spec));
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        String workerUserData = KubeadmUserData.worker(spec);
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, px, "worker-" + i, workerUserData));
        }
        // Proxmox 에는 VPC 가 없다. 계약상 vpcId 는 채워야 하므로 노드 이름을 넣는다.
        return new StandardOutputs.NodeRefs("sshKey", "image", "nodeName", master, workers);
    }

    /** cloud 이미지를 datastore 로 내려받는다. 노드마다 한 번이면 되므로 리소스 하나만 만든다. */
    private void emitImage(PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Proxmox px) {
        b.resource(
                "image",
                T_DOWNLOAD_FILE,
                Map.of(
                        "nodeName",
                        px.nodeName(),
                        "datastoreId",
                        px.snippetDatastoreId(),
                        "contentType",
                        "import",
                        "fileName",
                        spec.name() + "-base.img",
                        "url",
                        imageUrl(spec),
                        "overwrite",
                        false));
    }

    private StandardOutputs.NodeRef emitNode(
            PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Proxmox px, String node, String userData) {
        String snippet = "cloudinit-" + node;
        // user-data 는 스니펫 파일로 올려야 한다. VM 속성에 직접 넣는 자리가 없다.
        b.resource(
                snippet,
                T_FILE,
                Map.of(
                        "nodeName", px.nodeName(),
                        "datastoreId", px.snippetDatastoreId(),
                        "contentType", "snippets",
                        "sourceRaw",
                                Map.of("data", userData, "fileName", spec.name() + "-" + node + "-user-data.yaml")));

        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("nodeName", px.nodeName());
        vm.put("name", spec.name() + "-" + node);
        vm.put("description", "anycloud " + spec.name() + " " + node);
        vm.put("onBoot", true);
        vm.put("started", true);
        vm.put("cpu", Map.of("cores", cores(spec, node), "type", "host"));
        vm.put("memory", Map.of("dedicated", memoryMib(spec, node)));
        // QEMU agent 가 없으면 ipv4Addresses 가 빈 배열이라 output 이 비어 나온다.
        vm.put("agent", Map.of("enabled", true));
        vm.put("operatingSystem", Map.of("type", "l26"));
        vm.put(
                "disks",
                List.of(Map.of(
                        "interface", ROOT_DISK_INTERFACE,
                        "datastoreId", px.datastoreId(),
                        "importFrom", YamlRef.of("image", "id"),
                        "size", rootDiskGb(spec))));
        vm.put("networkDevices", List.of(Map.of("bridge", px.networkBridge(), "model", "virtio", "enabled", true)));
        vm.put(
                "initialization",
                Map.of(
                        "datastoreId", px.datastoreId(),
                        "interface", CLOUD_INIT_INTERFACE,
                        "userDataFileId", YamlRef.of(snippet, "id"),
                        "ipConfigs", List.of(Map.of("ipv4", Map.of("address", "dhcp")))));
        b.resource(node, T_VM, vm);

        // ipv4Addresses 는 인터페이스별 배열의 배열이다. 첫 NIC 의 첫 주소가 노드 IP 다.
        return new StandardOutputs.NodeRef(node, "id", node, "ipv4Addresses[0][0]", node, "ipv4Addresses[0][0]");
    }

    /** {@code "코어-메모리MiB"} 규약. Proxmox 는 인스턴스 타입이 없어 값을 직접 받는다. */
    private int cores(ClusterSpec spec, String node) {
        return part(instanceType(spec, node), 0, DEFAULT_CORES);
    }

    private int memoryMib(ClusterSpec spec, String node) {
        return part(instanceType(spec, node), 1, DEFAULT_MEMORY_MIB);
    }

    private String instanceType(ClusterSpec spec, String node) {
        return node.startsWith("master") ? spec.masterInstanceType() : spec.workerInstanceType();
    }

    private int part(String spec, int index, int fallback) {
        if (spec == null || spec.isBlank()) return fallback;
        String[] parts = spec.split("-");
        if (parts.length <= index) return fallback;
        try {
            int value = Integer.parseInt(parts[index].trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String imageUrl(ClusterSpec spec) {
        return (spec.osImage() != null && !spec.osImage().isBlank()) ? spec.osImage() : DEFAULT_IMAGE_URL;
    }

    private int rootDiskGb(ClusterSpec spec) {
        return spec.rootDiskSizeGb() > 0 ? spec.rootDiskSizeGb() : 50;
    }

    private static void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 config 누락: " + key);
        }
    }

    /** provider 전용 설정. 다른 CSP 의 spec 이 오면 assembler 가 provider 를 잘못 라우팅한 것이다. */
    private static ProviderSpec.Proxmox proxmox(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Proxmox p) return p;
        throw new IllegalStateException("Proxmox 설정이 없다: provider=" + spec.provider());
    }
}
