package io.aipaas.cluster.provisioning.program;

import java.util.List;
import java.util.Map;

/**
 * CSP 전용 설정. 공통 {@link ClusterSpec} 은 provider 이름만 알고 내부는 모른다.
 *
 * <p>평면 접두 키(provider 무관하게 나열)는 잘못된 조합을 막지 못하고 provider 별 독립 진화도
 * 안 된다 — {@code provider=aws} 인데 openstack 필드를 채워도 통과한다. sealed 로 닫아 emitter 가
 * 자기 타입만 받게 한다.
 *
 * <p>provider 전용 설정이 없는 CSP(AWS 등)는 {@code null} 이다.
 */
public sealed interface ProviderSpec {

    /** 설정 키 접두. {@code providerSpec.imageName} 처럼 중첩을 평면 map 에 담는다. */
    String PREFIX = "providerSpec.";

    record Gcp(String project) implements ProviderSpec {}

    record Azure(String resourceGroup) implements ProviderSpec {}

    record Oci(String compartmentId) implements ProviderSpec {}

    /**
     * Alibaba Cloud ECS.
     *
     * @param zone VSwitch 가 zone 단위라 리전만으로는 서브넷을 만들 수 없다 (예: ap-northeast-2a).
     *     계정과 인스턴스 타입마다 쓸 수 있는 zone 이 달라 리전에서 유도하지 않는다 — 자동으로
     *     고르면 재고 없음이 엉뚱한 오류로 나온다
     */
    record Alibaba(String zone) implements ProviderSpec {}

    record Openstack(String imageName, String flavorName, String externalNetworkId, String floatingIpPool)
            implements ProviderSpec {}

    /**
     * Proxmox 는 VPC, 서브넷, 보안그룹이 없다. 노드 위에 VM 을 올리고 기존 브리지에 붙인다.
     *
     * @param nodeName 배치할 PVE 노드. 클러스터라도 노드를 지정해야 한다
     * @param datastoreId 디스크와 cloud-init 디스크를 만들 datastore. 블록 스토리지여야 한다
     * @param imageDatastoreId 내려받은 cloud 이미지를 둘 datastore. import content type 을 받는
     *     디렉터리 스토리지여야 해서 {@code datastoreId} 와 같은 곳을 쓸 수 없다
     * @param networkBridge 붙일 브리지 (예: vmbr0)
     * @param nodeIps 노드에 고정할 IPv4 주소. master 가 먼저고 worker 가 뒤따른다. 비우면 DHCP 인데,
     *     cloud 이미지에 {@code qemu-guest-agent} 가 없어 받은 주소를 알아낼 방법이 없다
     * @param gateway 고정 주소를 쓸 때의 기본 게이트웨이
     * @param subnetPrefix 고정 주소의 prefix 길이 (예: 24)
     * @param dnsServers 쉼표로 이은 DNS 주소. 노드가 apt, 레지스트리로 나가야 한다
     * @param sshHost 부트스트랩이 붙을 주소. NAT 뒤라 노드 IP 로 직접 닿지 않을 때만 쓴다
     * @param sshPorts {@code sshHost} 의 포트. {@code nodeIps} 와 같은 순서여야 한다
     */
    record Proxmox(
            String nodeName,
            String datastoreId,
            String imageDatastoreId,
            String networkBridge,
            List<String> nodeIps,
            String gateway,
            Integer subnetPrefix,
            String dnsServers,
            String sshHost,
            List<Integer> sshPorts)
            implements ProviderSpec {

        /** 고정 주소를 쓰는지. 하나라도 있으면 전부 지정된 것으로 본다 — preflight 가 개수를 검증한다. */
        public boolean usesStaticAddressing() {
            return nodeIps != null && !nodeIps.isEmpty();
        }

        /** {@code sshHost} 가 없으면 노드 주소로 직접 붙는다. */
        public String sshHostOr(String nodeIp) {
            return sshHost == null || sshHost.isBlank() ? nodeIp : sshHost;
        }

        public int sshPortAt(int index) {
            return sshPorts == null || index >= sshPorts.size() ? 22 : sshPorts.get(index);
        }

        public String nodeIpAt(int index) {
            return nodeIps == null || index >= nodeIps.size() ? null : nodeIps.get(index);
        }
    }

    /**
     * IBM Cloud VPC.
     *
     * @param zone 인스턴스를 올릴 zone. region 만으로는 서브넷을 만들 수 없다 (예: kr-seo-1)
     * @param resourceGroup 리소스를 담을 그룹 ID. 생략하면 계정 기본 그룹
     */
    record Ibm(String zone, String resourceGroup) implements ProviderSpec {}

    /**
     * config map 에서 provider 에 맞는 spec 을 만든다. 인식하지 못하는 provider 는 {@code null}.
     *
     * @param lookup 키 하나를 읽는 함수. 호출자가 namespace 접두 처리를 소유한다.
     */
    static ProviderSpec from(String provider, java.util.function.Function<String, String> lookup) {
        if (provider == null) {
            return null;
        }
        return switch (ProviderName.canonical(provider)) {
            case "gcp" -> new Gcp(read(lookup, "project"));
            case "azure" -> new Azure(read(lookup, "resourceGroup"));
            case "oci" -> new Oci(read(lookup, "compartmentId"));
            case "alibaba" -> new Alibaba(read(lookup, "zone"));
            case "openstack" -> new Openstack(
                    read(lookup, "imageName"),
                    read(lookup, "flavorName"),
                    read(lookup, "externalNetworkId"),
                    read(lookup, "floatingIpPool"));
            case "ibm" -> new Ibm(read(lookup, "zone"), read(lookup, "resourceGroup"));
            case "proxmox" -> new Proxmox(
                    read(lookup, "nodeName"),
                    read(lookup, "datastoreId"),
                    read(lookup, "imageDatastoreId"),
                    read(lookup, "networkBridge"),
                    csv(read(lookup, "nodeIps")),
                    read(lookup, "gateway"),
                    integerOrNull(read(lookup, "subnetPrefix")),
                    read(lookup, "dnsServers"),
                    read(lookup, "sshHost"),
                    csv(read(lookup, "sshPorts")).stream()
                            .map(ProviderSpec::integerOrNull)
                            .filter(java.util.Objects::nonNull)
                            .toList());
            default -> null;
        };
    }

    /** {@link #from(String, java.util.function.Function)} 의 map 판. */
    static ProviderSpec from(String provider, Map<String, String> config) {
        Map<String, String> cfg = config == null ? Map.of() : config;
        return from(provider, cfg::get);
    }

    private static String read(java.util.function.Function<String, String> lookup, String key) {
        return lookup.apply(PREFIX + key);
    }

    /** 화면이 한 칸에 받으므로 쉼표로 이어 온다. 공백은 붙여 넣을 때 섞인다. */
    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** 값이 깨지면 기본값으로 조용히 넘어가지 않는다 — 노드마다 다른 주소가 붙으면 찾기 어렵다. */
    private static Integer integerOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아닌 값: " + value);
        }
    }
}
