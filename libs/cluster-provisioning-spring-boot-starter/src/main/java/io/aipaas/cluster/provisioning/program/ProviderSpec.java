package io.aipaas.cluster.provisioning.program;

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

    record Openstack(String imageName, String flavorName, String externalNetworkId, String floatingIpPool)
            implements ProviderSpec {}

    /**
     * Proxmox 는 VPC, 서브넷, 보안그룹이 없다. 노드 위에 VM 을 올리고 기존 브리지에 붙인다.
     *
     * @param nodeName 배치할 PVE 노드. 클러스터라도 노드를 지정해야 한다
     * @param datastoreId 디스크와 cloud-init 디스크를 만들 datastore
     * @param snippetDatastoreId user-data 스니펫을 올릴 datastore. snippets content type 이 켜져 있어야 한다
     * @param networkBridge 붙일 브리지 (예: vmbr0)
     */
    record Proxmox(String nodeName, String datastoreId, String snippetDatastoreId, String networkBridge)
            implements ProviderSpec {}

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
            case "openstack" -> new Openstack(
                    read(lookup, "imageName"),
                    read(lookup, "flavorName"),
                    read(lookup, "externalNetworkId"),
                    read(lookup, "floatingIpPool"));
            case "proxmox" -> new Proxmox(
                    read(lookup, "nodeName"),
                    read(lookup, "datastoreId"),
                    read(lookup, "snippetDatastoreId"),
                    read(lookup, "networkBridge"));
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
}
