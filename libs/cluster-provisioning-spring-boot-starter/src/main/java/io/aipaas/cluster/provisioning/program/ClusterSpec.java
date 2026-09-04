package io.aipaas.cluster.provisioning.program;

import com.pulumi.Config;
import com.pulumi.Context;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pulumi program 안에서 ctx.config() 로부터 읽어들이는 정규화된 cluster spec.
 *
 * <p>{@link #load(Context)} 로 raw spec 빌드 → {@link #normalize()} 로 provider 별 default + cross-cutting
 * 보정 (masterCount odd 강제, rootDiskSizeGb≥50) 후 provisioner 에 전달. PoC 단계라 control-plane HA 는
 * 단일 master endpoint 기반 (VIP/LB 미적용).
 */
public record ClusterSpec(
        String provider,
        String name,
        String environment,
        String region,
        String gcpProject,
        String azureResourceGroup,
        String ociCompartmentId,
        String vpcCidr,
        List<String> subnetCidrs,
        String sshUser,
        String masterInstanceType,
        String workerInstanceType,
        int masterCount,
        int workerCount,
        String kubernetesVersion,
        String podCidr,
        String serviceCidr,
        String joinToken,
        String openstackImageName,
        String openstackFlavorName,
        String openstackExternalNetworkId,
        String openstackFloatingIpPool,
        DatabaseSpec database,
        boolean useSpot,
        String osImage,
        int rootDiskSizeGb) {

    /** Pulumi stack config (namespace=anycloud-k8s) 으로부터 raw spec 빌드. defaults 미적용 상태. */
    public static ClusterSpec load(Context ctx) {
        Config cfg = ctx.config("anycloud-k8s");
        return new ClusterSpec(
                str(cfg, "provider"),
                str(cfg, "name"),
                str(cfg, "environment"),
                str(cfg, "region"),
                str(cfg, "gcpProject"),
                str(cfg, "azureResourceGroup"),
                str(cfg, "ociCompartmentId"),
                str(cfg, "vpcCidr"),
                strList(cfg, "subnetCidrs"),
                str(cfg, "sshUser"),
                str(cfg, "masterInstanceType"),
                str(cfg, "workerInstanceType"),
                intVal(cfg, "masterCount"),
                intVal(cfg, "workerCount"),
                str(cfg, "kubernetesVersion"),
                str(cfg, "podCidr"),
                str(cfg, "serviceCidr"),
                str(cfg, "joinToken"),
                str(cfg, "openstackImageName"),
                str(cfg, "openstackFlavorName"),
                str(cfg, "openstackExternalNetworkId"),
                str(cfg, "openstackFloatingIpPool"),
                new DatabaseSpec(
                        boolVal(cfg, "dbEnabled"),
                        str(cfg, "dbName"),
                        str(cfg, "dbUsername"),
                        str(cfg, "dbPassword"),
                        str(cfg, "dbInstanceClass"),
                        intVal(cfg, "dbAllocatedStorageGb"),
                        boolVal(cfg, "dbPubliclyAccessible")),
                boolVal(cfg, "useSpot"),
                str(cfg, "osImage"),
                intVal(cfg, "rootDiskSizeGb"));
    }

    /** Provider 별 default + cross-cutting 보정 적용. {@link Defaults#applyProviderDefaults} 위임. */
    public ClusterSpec normalize() {
        return Defaults.applyProviderDefaults(this);
    }

    /** Mutable builder 진입점. record 의 모든 필드를 복사한 빌더 반환. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** OS image — provider 별 default 반환. {@link Defaults#resolvedOsImage} 위임. */
    public String osImageOrDefault() {
        return Defaults.resolvedOsImage(this);
    }

    /**
     * Pulumi Context 없이 config map 으로부터 raw spec 빌드. defaults 미적용.
     *
     * <p>YAML 프로그램 경로용이다 — 프로그램을 JVM 안에서 실행하지 않으므로 {@code Context} 가 없다.
     * 키는 namespace 접두 유무 양쪽을 받는다. 호출자가 Pulumi config 원형
     * ({@code anycloud-k8s:workerCount}) 을 그대로 넘길 수 있다.
     */
    public static ClusterSpec from(Map<String, String> config) {
        Map<String, String> cfg = config == null ? Map.of() : config;
        return new ClusterSpec(
                mapStr(cfg, "provider"),
                mapStr(cfg, "name"),
                mapStr(cfg, "environment"),
                mapStr(cfg, "region"),
                mapStr(cfg, "gcpProject"),
                mapStr(cfg, "azureResourceGroup"),
                mapStr(cfg, "ociCompartmentId"),
                mapStr(cfg, "vpcCidr"),
                mapList(cfg, "subnetCidrs"),
                mapStr(cfg, "sshUser"),
                mapStr(cfg, "masterInstanceType"),
                mapStr(cfg, "workerInstanceType"),
                mapInt(cfg, "masterCount"),
                mapInt(cfg, "workerCount"),
                mapStr(cfg, "kubernetesVersion"),
                mapStr(cfg, "podCidr"),
                mapStr(cfg, "serviceCidr"),
                mapStr(cfg, "joinToken"),
                mapStr(cfg, "openstackImageName"),
                mapStr(cfg, "openstackFlavorName"),
                mapStr(cfg, "openstackExternalNetworkId"),
                mapStr(cfg, "openstackFloatingIpPool"),
                new DatabaseSpec(
                        mapBool(cfg, "dbEnabled"),
                        mapStr(cfg, "dbName"),
                        mapStr(cfg, "dbUsername"),
                        mapStr(cfg, "dbPassword"),
                        mapStr(cfg, "dbInstanceClass"),
                        mapInt(cfg, "dbAllocatedStorageGb"),
                        mapBool(cfg, "dbPubliclyAccessible")),
                mapBool(cfg, "useSpot"),
                mapStr(cfg, "osImage"),
                mapInt(cfg, "rootDiskSizeGb"));
    }

    private static final String CONFIG_NAMESPACE = "anycloud-k8s:";

    /** namespace 접두가 있든 없든 같은 키로 읽는다. */
    private static String mapStr(Map<String, String> cfg, String key) {
        String value = cfg.get(key);
        return value != null ? value : cfg.get(CONFIG_NAMESPACE + key);
    }

    /** 잘못된 값에 예외를 던지면 Defaults 가 손쓸 기회 없이 프로비저닝이 죽는다. */
    private static int mapInt(Map<String, String> cfg, String key) {
        String value = mapStr(cfg, key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean mapBool(Map<String, String> cfg, String key) {
        return Boolean.parseBoolean(mapStr(cfg, key));
    }

    private static List<String> mapList(Map<String, String> cfg, String key) {
        String value = mapStr(cfg, key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static String str(Config cfg, String key) {
        try {
            return cfg.get(key).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int intVal(Config cfg, String key) {
        try {
            return cfg.getInteger(key).orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean boolVal(Config cfg, String key) {
        try {
            return cfg.getBoolean(key).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Config cfg, String key) {
        try {
            Optional<Object> obj = cfg.getObject(key, Object.class);
            if (obj.isEmpty()) return Collections.emptyList();
            if (obj.get() instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * ClusterSpec 의 mutable builder. {@link #toBuilder()} 로 초기화 → 수정할 필드만 chain → {@link #build()}.
     * 미설정 필드는 원본 값 보존. 18-arg positional constructor 의 fragility 제거.
     */
    public static final class Builder {
        private String provider;
        private String name;
        private String environment;
        private String region;
        private String gcpProject;
        private String azureResourceGroup;
        private String ociCompartmentId;
        private String vpcCidr;
        private List<String> subnetCidrs;
        private String sshUser;
        private String masterInstanceType;
        private String workerInstanceType;
        private int masterCount;
        private int workerCount;
        private String kubernetesVersion;
        private String podCidr;
        private String serviceCidr;
        private String joinToken;
        private String openstackImageName;
        private String openstackFlavorName;
        private String openstackExternalNetworkId;
        private String openstackFloatingIpPool;
        private DatabaseSpec database;
        private boolean useSpot;
        private String osImage;
        private int rootDiskSizeGb;

        private Builder(ClusterSpec src) {
            this.provider = src.provider;
            this.name = src.name;
            this.environment = src.environment;
            this.region = src.region;
            this.gcpProject = src.gcpProject;
            this.azureResourceGroup = src.azureResourceGroup;
            this.ociCompartmentId = src.ociCompartmentId;
            this.vpcCidr = src.vpcCidr;
            this.subnetCidrs = src.subnetCidrs;
            this.sshUser = src.sshUser;
            this.masterInstanceType = src.masterInstanceType;
            this.workerInstanceType = src.workerInstanceType;
            this.masterCount = src.masterCount;
            this.workerCount = src.workerCount;
            this.kubernetesVersion = src.kubernetesVersion;
            this.podCidr = src.podCidr;
            this.serviceCidr = src.serviceCidr;
            this.joinToken = src.joinToken;
            this.openstackImageName = src.openstackImageName;
            this.openstackFlavorName = src.openstackFlavorName;
            this.openstackExternalNetworkId = src.openstackExternalNetworkId;
            this.openstackFloatingIpPool = src.openstackFloatingIpPool;
            this.database = src.database;
            this.useSpot = src.useSpot;
            this.osImage = src.osImage;
            this.rootDiskSizeGb = src.rootDiskSizeGb;
        }

        public Builder provider(String v) {
            this.provider = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder environment(String v) {
            this.environment = v;
            return this;
        }

        public Builder vpcCidr(String v) {
            this.vpcCidr = v;
            return this;
        }

        public Builder subnetCidrs(List<String> v) {
            this.subnetCidrs = v;
            return this;
        }

        public Builder sshUser(String v) {
            this.sshUser = v;
            return this;
        }

        public Builder masterInstanceType(String v) {
            this.masterInstanceType = v;
            return this;
        }

        public Builder workerInstanceType(String v) {
            this.workerInstanceType = v;
            return this;
        }

        public Builder masterCount(int v) {
            this.masterCount = v;
            return this;
        }

        public Builder workerCount(int v) {
            this.workerCount = v;
            return this;
        }

        public Builder kubernetesVersion(String v) {
            this.kubernetesVersion = v;
            return this;
        }

        public Builder podCidr(String v) {
            this.podCidr = v;
            return this;
        }

        public Builder serviceCidr(String v) {
            this.serviceCidr = v;
            return this;
        }

        public Builder joinToken(String v) {
            this.joinToken = v;
            return this;
        }

        public Builder database(DatabaseSpec v) {
            this.database = v;
            return this;
        }

        public Builder rootDiskSizeGb(int v) {
            this.rootDiskSizeGb = v;
            return this;
        }

        public Builder openstackImageName(String v) {
            this.openstackImageName = v;
            return this;
        }

        public Builder openstackFlavorName(String v) {
            this.openstackFlavorName = v;
            return this;
        }

        public Builder azureResourceGroup(String v) {
            this.azureResourceGroup = v;
            return this;
        }

        public ClusterSpec build() {
            return new ClusterSpec(
                    provider,
                    name,
                    environment,
                    region,
                    gcpProject,
                    azureResourceGroup,
                    ociCompartmentId,
                    vpcCidr,
                    subnetCidrs,
                    sshUser,
                    masterInstanceType,
                    workerInstanceType,
                    masterCount,
                    workerCount,
                    kubernetesVersion,
                    podCidr,
                    serviceCidr,
                    joinToken,
                    openstackImageName,
                    openstackFlavorName,
                    openstackExternalNetworkId,
                    openstackFloatingIpPool,
                    database,
                    useSpot,
                    osImage,
                    rootDiskSizeGb);
        }
    }
}
