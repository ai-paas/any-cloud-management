package io.aipaas.cluster.provisioning.program;

import java.util.List;
import java.util.Map;

/**
 * CSP 전용 설정. 공통 {@link ClusterSpec} 은 provider 이름만 보유하고 내부 구조는 미인지.
 *
 * <p>평면 접두 키(provider 무관 나열)는 잘못된 조합을 차단하지 못하고 provider 별 독립 확장도
 * 불가. {@code provider=aws} 상태에서 openstack 필드를 채워도 통과. sealed 로 닫아 emitter 가
 * 자기 타입만 수용.
 *
 * <p>provider 전용 설정이 없는 CSP(AWS 등)는 {@code null}.
 */
public sealed interface ProviderSpec {

    /** 설정 키 접두. {@code providerSpec.imageName} 형태로 중첩 구조를 평면 map 에 저장. */
    String PREFIX = "providerSpec.";

    /**
     * @param zone 미지정 시 {@code region-a}. 계열별 제공 존이 달라 GPU 는 존 지정 필요.
     *     a2-highgpu 는 리전에 있어도 -a 존에서 생성 불가한 경우 존재
     */
    record Gcp(String project, String zone) implements ProviderSpec {}

    record Azure(String resourceGroup) implements ProviderSpec {}

    record Oci(String compartmentId) implements ProviderSpec {}

    /**
     * Alibaba Cloud ECS.
     *
     * @param zone VSwitch 가 zone 단위이므로 리전만으로 서브넷 생성 불가 (예: ap-northeast-2a).
     *     계정과 인스턴스 타입별 사용 가능 zone 이 달라 리전에서 유도하지 않음. 자동 선택 시
     *     재고 없음이 다른 오류로 표시
     */
    record Alibaba(String zone) implements ProviderSpec {}

    record Openstack(String imageName, String flavorName, String externalNetworkId, String floatingIpPool)
            implements ProviderSpec {}

    /**
     * Proxmox 는 VPC, 서브넷, 보안그룹 개념 부재. 노드 위에 VM 을 생성하고 기존 브리지에 연결.
     *
     * @param nodeName 배치할 PVE 노드. 클러스터 구성에서도 노드 지정 필요
     * @param datastoreId 디스크와 cloud-init 디스크 생성용 datastore. 블록 스토리지 필요
     * @param imageDatastoreId 수신한 cloud 이미지 저장용 datastore. import content type 을
     *     허용하는 디렉터리 스토리지가 필요하므로 {@code datastoreId} 와 동일 지정 불가
     * @param networkBridge 연결할 브리지 (예: vmbr0)
     * @param nodeIps 노드에 고정할 IPv4 주소. master 선행, worker 후행. 미지정 시 DHCP 이나
     *     cloud 이미지에 {@code qemu-guest-agent} 가 없어 할당 주소 조회 불가
     * @param gateway 고정 주소 사용 시 기본 게이트웨이
     * @param subnetPrefix 고정 주소의 prefix 길이 (예: 24)
     * @param dnsServers 쉼표로 구분한 DNS 주소. 노드의 apt, 레지스트리 접근에 필요
     * @param sshHost 부트스트랩 접속 주소. NAT 뒤라 노드 IP 직접 접근이 불가할 때만 사용
     * @param sshPorts {@code sshHost} 의 포트. {@code nodeIps} 와 동일 순서 필요
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

        /** 고정 주소 사용 여부. 하나라도 있으면 전체 지정으로 판정. 개수 검증은 preflight 담당. */
        public boolean usesStaticAddressing() {
            return nodeIps != null && !nodeIps.isEmpty();
        }

        /** {@code sshHost} 부재 시 노드 주소로 직접 접속. */
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
     * config map 에서 provider 에 해당하는 spec 생성. 미인식 provider 는 {@code null}.
     *
     * @param lookup 키 단건 조회 함수. namespace 접두 처리는 호출자 책임
     */
    static ProviderSpec from(String provider, java.util.function.Function<String, String> lookup) {
        if (provider == null) {
            return null;
        }
        return switch (ProviderName.canonical(provider)) {
            case "gcp" -> new Gcp(read(lookup, "project"), read(lookup, "zone"));
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

    /** 값이 깨지면 기본값으로 조용히 넘어가지 않는다. 노드마다 다른 주소가 붙으면 찾기 어렵다. */
    private static Integer integerOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아닌 값: " + value);
        }
    }
}
