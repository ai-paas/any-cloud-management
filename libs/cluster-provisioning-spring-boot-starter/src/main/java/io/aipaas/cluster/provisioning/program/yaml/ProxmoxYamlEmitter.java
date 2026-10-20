package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.ProviderSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxmox VE 노드 위에 VM 생성.
 *
 * <p>다른 CSP 와 달리 VPC, 서브넷, 보안그룹 미생성. Proxmox 는 하이퍼바이저라 네트워크가 기존
 * 브리지이고 방화벽은 노드 단위 운영자 관리. 따라서 vpcCidr 과 subnetCidrs 는 이 emitter 에서
 * 미사용.
 */
final class ProxmoxYamlEmitter implements ProviderYamlEmitter {

    private static final String T_DOWNLOAD_FILE = "proxmoxve:download/file:File";
    private static final String T_VM = "proxmoxve:index/vmLegacy:VmLegacy";
    private static final String T_PRIVATE_KEY = "tls:index/privateKey:PrivateKey";

    private static final String DEFAULT_IMAGE_URL =
            "https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-amd64.img";

    private static final String ROOT_DISK_INTERFACE = "scsi0";

    /** cloud-init 디스크는 IDE 에만 연결 가능. scsi0 는 루트 디스크가 점유. */
    private static final String CLOUD_INIT_INTERFACE = "ide2";

    private static final int DEFAULT_CORES = 2;
    private static final int DEFAULT_MEMORY_MIB = 4096;

    /** {@code PVE::Storage::IMPORT_EXT_RE_1} 과 같은 목록. */
    private static final java.util.Set<String> IMPORT_EXTENSIONS =
            java.util.Set.of(".ova", ".ovf", ".qcow2", ".raw", ".vmdk");

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

        // nodeIps 순서 계약. master 가 0, worker 가 1부터. 불일치 시 노드에 다른 노드 주소 할당.
        StandardOutputs.NodeRef master = emitNode(b, spec, px, "master", 0);
        List<StandardOutputs.NodeRef> workers = new ArrayList<>();
        for (int i = 1; i <= spec.workerCount(); i++) {
            workers.add(emitNode(b, spec, px, "worker-" + i, i));
        }
        // Proxmox 에는 VPC 부재. 계약상 vpcId 가 필수이므로 노드 이름으로 대체.
        return new StandardOutputs.NodeRefs("sshKey", "image", "nodeName", master, workers);
    }

    /** cloud 이미지를 datastore 로 수신. 노드당 1회면 충분하므로 리소스 1개만 생성. */
    private void emitImage(PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Proxmox px) {
        b.resource(
                "image",
                T_DOWNLOAD_FILE,
                Map.of(
                        "nodeName",
                        px.nodeName(),
                        "datastoreId",
                        px.imageDatastoreId(),
                        "contentType",
                        "import",
                        "fileName",
                        imageFileName(spec),
                        "url",
                        imageUrl(spec),
                        "overwrite",
                        false));
    }

    private StandardOutputs.NodeRef emitNode(
            PulumiProgram.Builder b, ClusterSpec spec, ProviderSpec.Proxmox px, String node, int index) {
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("nodeName", px.nodeName());
        vm.put("name", spec.name() + "-" + node);
        vm.put("description", "anycloud " + spec.name() + " " + node);
        vm.put("onBoot", true);
        vm.put("started", true);
        vm.put("cpu", Map.of("cores", cores(spec, node), "type", "host"));
        vm.put("memory", Map.of("dedicated", memoryMib(spec, node)));
        /*
         * agent 활성화 시 provider 가 VM 생성 완료 전에 agent 의 IP 보고를 대기. Ubuntu cloud
         * 이미지에 qemu-guest-agent 가 없고 user-data 설치도 불가하므로, 주소를 직접 지정한
         * 경우 도달하지 않는 응답을 무한 대기.
         */
        vm.put("agent", Map.of("enabled", !px.usesStaticAddressing()));
        vm.put("operatingSystem", Map.of("type", "l26"));
        vm.put(
                "disks",
                List.of(Map.of(
                        "interface", ROOT_DISK_INTERFACE,
                        "datastoreId", px.datastoreId(),
                        "importFrom", YamlRef.of("image", "id"),
                        "size", rootDiskGb(spec))));
        vm.put("networkDevices", List.of(Map.of("bridge", px.networkBridge(), "model", "virtio")));
        /*
         * fileId 기본값 cdrom 은 빈 CD-ROM 드라이브. PVE 가 이를 물리 장치 연결로 판정해 / 의
         * Sys.Console 권한을 요구하고 VM 생성이 403 거절. 적재할 매체가 없으므로 미연결.
         */
        vm.put("cdrom", Map.of("fileId", "none"));
        /*
         * 공개키는 API 로 주입. user-data 미사용. Proxmox API 가 snippets 업로드를 지원하지
         * 않아 전달에 PVE 호스트 SSH 접근 권한이 필요. 패키지 설치는 부트스트랩이 노드 SSH 로 수행.
         */
        Map<String, Object> initialization = new LinkedHashMap<>();
        initialization.put("datastoreId", px.datastoreId());
        initialization.put("interface", CLOUD_INIT_INTERFACE);
        initialization.put(
                "userAccount",
                Map.of("username", sshUser(spec), "keys", List.of(YamlRef.of("sshKey", "publicKeyOpenssh"))));
        initialization.put("ipConfigs", List.of(Map.of("ipv4", ipv4Config(px, index))));
        if (px.usesStaticAddressing()
                && px.dnsServers() != null
                && !px.dnsServers().isBlank()) {
            // 고정 주소를 쓰면 DHCP 가 주던 resolver 도 사라진다. 없으면 apt 와 레지스트리가 막힌다.
            initialization.put("dns", Map.of("servers", splitCsv(px.dnsServers())));
        }
        vm.put("initialization", initialization);
        b.resource(node, T_VM, vm);

        String nodeIp = px.nodeIpAt(index);
        if (nodeIp != null) {
            /*
             * 주소를 물어보지 않는다. cloud 이미지에 qemu-guest-agent 가 없어 ipv4Addresses 가 영영
             * 비고, Pulumi 가 그 값을 기다리며 멈춘다.
             */
            return StandardOutputs.NodeRef.literal(node, "id", nodeIp, px.sshHostOr(nodeIp), px.sshPortAt(index));
        }
        // ipv4Addresses 는 인터페이스별 배열의 배열이다. 첫 NIC 의 첫 주소가 노드 IP 다.
        return new StandardOutputs.NodeRef(node, "id", node, "ipv4Addresses[0][0]", node, "ipv4Addresses[0][0]");
    }

    /** 고정 주소가 없으면 DHCP 로 둔다. 주소를 알 수 없어 부트스트랩은 못 붙지만 VM 은 뜬다. */
    private Map<String, Object> ipv4Config(ProviderSpec.Proxmox px, int index) {
        String nodeIp = px.nodeIpAt(index);
        if (nodeIp == null) {
            return Map.of("address", "dhcp");
        }
        int prefix = px.subnetPrefix() == null ? 24 : px.subnetPrefix();
        Map<String, Object> ipv4 = new LinkedHashMap<>();
        ipv4.put("address", nodeIp + "/" + prefix);
        if (px.gateway() != null && !px.gateway().isBlank()) {
            ipv4.put("gateway", px.gateway());
        }
        return ipv4;
    }

    /** {@code "코어-메모리MiB"} 규약. Proxmox 는 인스턴스 타입이 없어 값을 직접 수신. */
    private int cores(ClusterSpec spec, String node) {
        return part(instanceType(spec, node), 0, DEFAULT_CORES);
    }

    private int memoryMib(ClusterSpec spec, String node) {
        return part(instanceType(spec, node), 1, DEFAULT_MEMORY_MIB);
    }

    private String sshUser(ClusterSpec spec) {
        return (spec.sshUser() != null && !spec.sshUser().isBlank()) ? spec.sshUser() : "ubuntu";
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

    /**
     * PVE 가 {@code import} content 로 허용하는 확장자는 {@code .ova .ovf .qcow2 .raw .vmdk}.
     *
     * <p>Ubuntu cloud 이미지는 {@code .img} 로 배포되나 내용은 qcow2. URL 이름을 그대로 사용하면
     * {@code invalid filename or wrong extension} 으로 400 응답.
     */
    private String imageFileName(ClusterSpec spec) {
        String url = imageUrl(spec);
        int dot = url.lastIndexOf('.');
        String extension = dot < 0 ? "" : url.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return spec.name() + "-base" + (IMPORT_EXTENSIONS.contains(extension) ? extension : ".qcow2");
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

    /**
     * 쉼표로 구분한 값을 항목으로 분리.
     *
     * <p>구분자 정규식에 {@code \s*} 를 두면 공백 길이에 따라 매칭 비용이 제곱 증가
     * (polynomial ReDoS). 값이 외부 입력이므로 고정 문자로 분리하고 공백은 별도 제거.
     */
    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /** provider 전용 설정. 다른 CSP 의 spec 수신은 assembler 의 provider 라우팅 오류. */
    private static ProviderSpec.Proxmox proxmox(ClusterSpec spec) {
        if (spec.providerSpec() instanceof ProviderSpec.Proxmox p) return p;
        throw new IllegalStateException("Proxmox 설정이 없다: provider=" + spec.provider());
    }
}
