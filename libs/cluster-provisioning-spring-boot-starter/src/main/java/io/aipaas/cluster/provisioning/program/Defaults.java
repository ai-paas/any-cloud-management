package io.aipaas.cluster.provisioning.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider 별 default 적용 + cross-cutting 보정.
 *
 * <p>caller 가 제공한 ClusterSpec 의 빈 필드를 provider 의 권장 값으로 채워 새 record 반환.
 * cross-cutting default (masterCount odd 강제 — etcd quorum split-brain 방지, rootDiskSizeGb≥50 — k8s
 * NodeHasDiskPressure 방지) 도 본 메서드가 적용.
 */
public final class Defaults {

    private Defaults() {}

    private static final String DEFAULT_ENVIRONMENT = "dev";
    private static final String DEFAULT_K8S_VERSION = "1.31";
    private static final String DEFAULT_POD_CIDR = "192.168.0.0/16";
    private static final String DEFAULT_SERVICE_CIDR = "10.96.0.0/12";
    private static final int DEFAULT_WORKER_COUNT = 2;
    private static final int DEFAULT_ROOT_DISK_GB = 50;

    /**
     * Provider 별 default 값 테이블. 새 CSP 추가 시 entry 1줄 + (선택) {@link #applyProviderSpecific}
     * 의 case 추가.
     */
    private record ProviderDefaults(
            String name,
            String vpcCidr,
            String masterInstanceType,
            String workerInstanceType,
            String sshUser) {}

    private static final Map<String, ProviderDefaults> TABLE = Map.of(
            "aws",          new ProviderDefaults("anycloud-demo",         "10.42.0.0/16", "t3.large",            "t3.large",            "ubuntu"),
            "gcp",          new ProviderDefaults("anycloud-gcp",          "10.52.0.0/16", "e2-standard-2",       "e2-standard-2",       "ubuntu"),
            "azure",        new ProviderDefaults("anycloud-azure",        "10.62.0.0/16", "Standard_D4s_v5",     "Standard_D4s_v5",     "ubuntu"),
            "alibaba",      new ProviderDefaults("anycloud-alibaba",      "10.72.0.0/16", "ecs.g6.large",        "ecs.g6.large",        "ubuntu"),
            "openstack",    new ProviderDefaults("anycloud-openstack",    "10.90.0.0/24", null,                  null,                  "ubuntu"),
            "oci",          new ProviderDefaults("anycloud-oci",          "10.86.0.0/16", "VM.Standard.E4.Flex", "VM.Standard.E4.Flex", "ubuntu"),
            "digitalocean", new ProviderDefaults("anycloud-digitalocean", "10.88.0.0/16", "s-2vcpu-4gb",         "s-2vcpu-4gb",         "root"));

    public static ClusterSpec applyProviderDefaults(ClusterSpec raw) {
        String canonical = ProviderName.canonical(raw.provider());
        ProviderDefaults pd = TABLE.get(canonical);
        if (pd == null) return raw.toBuilder().provider(canonical).build();

        ClusterSpec.Builder b = raw.toBuilder()
                .provider(canonical)
                .name(blankOr(raw.name(), pd.name()))
                .environment(blankOr(raw.environment(), DEFAULT_ENVIRONMENT))
                .vpcCidr(blankOr(raw.vpcCidr(), pd.vpcCidr()))
                .sshUser(blankOr(raw.sshUser(), pd.sshUser()))
                .workerCount(raw.workerCount() == 0 ? DEFAULT_WORKER_COUNT : raw.workerCount())
                .kubernetesVersion(blankOr(raw.kubernetesVersion(), DEFAULT_K8S_VERSION))
                .podCidr(blankOr(raw.podCidr(), DEFAULT_POD_CIDR))
                .serviceCidr(blankOr(raw.serviceCidr(), DEFAULT_SERVICE_CIDR))
                .joinToken(JoinTokens.ensure(raw.joinToken()));

        if (pd.masterInstanceType() != null) {
            b.masterInstanceType(blankOr(raw.masterInstanceType(), pd.masterInstanceType()))
                    .workerInstanceType(blankOr(raw.workerInstanceType(), pd.workerInstanceType()));
        }
        applyProviderSpecific(canonical, raw, b);

        ClusterSpec withProvider = b.build();
        List<String> subnets = ensureDefaultSubnets(
                withProvider.vpcCidr(), withProvider.subnetCidrs(), requiredSubnetCount(canonical));

        int masterCount = withProvider.masterCount();
        if (masterCount <= 0) masterCount = 1;
        else if (masterCount % 2 == 0) masterCount = masterCount + 1;

        int rootDisk = withProvider.rootDiskSizeGb() <= 0 ? DEFAULT_ROOT_DISK_GB : withProvider.rootDiskSizeGb();

        return withProvider.toBuilder()
                .subnetCidrs(subnets)
                .masterCount(masterCount)
                .rootDiskSizeGb(rootDisk)
                .build();
    }

    /** CSP-specific 필드 (Azure RG / OpenStack image+flavor / AWS database) — table 으로 표현 어려운 case. */
    private static void applyProviderSpecific(String canonical, ClusterSpec raw, ClusterSpec.Builder b) {
        switch (canonical) {
            case "aws" -> b.database(applyDbDefaults(raw.database(), "anycloud", "anycloud", "db.t4g.micro", 20));
            case "azure" -> {
                String name = blankOr(raw.name(), TABLE.get("azure").name());
                b.azureResourceGroup(blankOr(raw.azureResourceGroup(), name + "-rg"));
            }
            case "openstack" -> {
                String flavor = blankOr(raw.openstackFlavorName(), "m1.large");
                b.masterInstanceType(blankOr(raw.masterInstanceType(), flavor))
                        .workerInstanceType(blankOr(raw.workerInstanceType(), flavor))
                        .openstackImageName(blankOr(raw.openstackImageName(), "ubuntu-24.04"))
                        .openstackFlavorName(flavor);
            }
            default -> { /* no extras */ }
        }
    }

    public static String resolvedOsImage(ClusterSpec spec) {
        return switch (ProviderName.canonical(spec.provider())) {
            case "openstack" -> spec.openstackImageName();
            case "gcp" -> "ubuntu-2404-lts";
            case "azure" -> "Canonical Ubuntu 24.04 LTS";
            case "alibaba", "oci", "digitalocean" -> "Ubuntu 24.04";
            default -> "ubuntu-24.04";
        };
    }

    private static DatabaseSpec applyDbDefaults(
            DatabaseSpec db, String name, String username, String instanceClass, int storageGb) {
        if (db == null) return DatabaseSpec.disabled();
        if (!db.enabled()) return db;
        return new DatabaseSpec(
                true,
                blankOr(db.name(), name),
                blankOr(db.username(), username),
                db.password(),
                blankOr(db.instanceClass(), instanceClass),
                db.allocatedStorageGb() == 0 ? storageGb : db.allocatedStorageGb(),
                db.publiclyAccessible());
    }

    private static String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static int requiredSubnetCount(String provider) {
        return "aws".equals(provider) ? 2 : 1;
    }

    /** IPv4 VPC CIDR 안의 subnet block 들을 균등 분할로 생성. existing 가 부족하면 generated 로 보강. */
    static List<String> ensureDefaultSubnets(String vpcCidr, List<String> existing, int required) {
        List<String> subnets = new ArrayList<>(existing == null ? List.of() : existing);
        if (subnets.size() >= required) return subnets;
        List<String> generated = generateSubnets(vpcCidr, required);
        if (subnets.isEmpty()) return generated;
        for (String cidr : generated) {
            if (subnets.size() >= required) break;
            if (!subnets.contains(cidr)) subnets.add(cidr);
        }
        return subnets;
    }

    static List<String> generateSubnets(String vpcCidr, int count) {
        if (vpcCidr == null) return List.of();
        int slash = vpcCidr.indexOf('/');
        if (slash < 0) return List.of();
        String ipStr = vpcCidr.substring(0, slash);
        int maskSize;
        try {
            maskSize = Integer.parseInt(vpcCidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return List.of();
        }
        long ip = parseIpv4(ipStr);
        if (ip < 0) return List.of();

        int subnetMaskSize = Math.max(maskSize, 24);
        if (subnetMaskSize > 30) subnetMaskSize = maskSize;
        long step = 1L << (32 - subnetMaskSize);
        long mask = maskSize == 0 ? 0L : (0xFFFFFFFFL << (32 - maskSize)) & 0xFFFFFFFFL;
        long base = ip & mask;

        List<String> subnets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long current = base + (long) i * step;
            subnets.add(formatIpv4(current) + "/" + subnetMaskSize);
        }
        return subnets;
    }

    private static long parseIpv4(String s) {
        String[] octets = s.split("\\.");
        if (octets.length != 4) return -1;
        long v = 0;
        for (String octet : octets) {
            int o;
            try {
                o = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (o < 0 || o > 255) return -1;
            v = (v << 8) | o;
        }
        return v;
    }

    private static String formatIpv4(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
