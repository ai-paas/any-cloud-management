package io.aipaas.cluster.provisioning.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * CSP credential 의 env 변수 (예: AWS_ACCESS_KEY_ID) 를 Pulumi stack config key (예: aws:accessKey)
 * 로 변환. Pulumi state backend (RustFS) 와 CSP provider 가 같은 env namespace (AWS_*) 를
 * 공유하던 충돌을 해소.
 *
 * <p>변환된 key 는 Pulumi default provider 가 자동 인식 (e.g. {@code aws:accessKey} → AWS SDK
 * static credential, {@code gcp:credentials} → GCP service account 등). 모든 value 는 stack
 * config 에 secret 으로 저장.
 */
public final class CspCredentialPulumiConfigMapper {

    private CspCredentialPulumiConfigMapper() {}

    /** Provider 별 env → config key 매퍼. 새 CSP 추가 시 entry 1줄만 추가. */
    private static final Map<String, BiConsumer<Map<String, String>, Map<String, String>>> MAPPERS = Map.of(
            "aws", (env, out) -> {
                put(out, "aws:accessKey", env.get("AWS_ACCESS_KEY_ID"));
                put(out, "aws:secretKey", env.get("AWS_SECRET_ACCESS_KEY"));
                put(out, "aws:token", env.get("AWS_SESSION_TOKEN"));
            },
            "gcp", (env, out) -> {
                String inline = env.get("GOOGLE_CREDENTIALS");
                put(out, "gcp:credentials",
                        (inline != null && !inline.isBlank()) ? inline : env.get("GOOGLE_APPLICATION_CREDENTIALS"));
            },
            "azure", (env, out) -> {
                put(out, "azure-native:clientId", env.get("ARM_CLIENT_ID"));
                put(out, "azure-native:clientSecret", env.get("ARM_CLIENT_SECRET"));
                put(out, "azure-native:subscriptionId", env.get("ARM_SUBSCRIPTION_ID"));
                put(out, "azure-native:tenantId", env.get("ARM_TENANT_ID"));
            },
            "alibaba", (env, out) -> {
                put(out, "alicloud:accessKey", env.get("ALICLOUD_ACCESS_KEY"));
                put(out, "alicloud:secretKey", env.get("ALICLOUD_SECRET_KEY"));
            },
            "openstack", (env, out) -> {
                put(out, "openstack:authUrl", env.get("OS_AUTH_URL"));
                put(out, "openstack:userName", env.get("OS_USERNAME"));
                put(out, "openstack:password", env.get("OS_PASSWORD"));
                put(out, "openstack:tenantName", env.get("OS_PROJECT_NAME"));
            },
            "oci", (env, out) -> {
                put(out, "oci:tenancyOcid", env.get("OCI_TENANCY_OCID"));
                put(out, "oci:userOcid", env.get("OCI_USER_OCID"));
                put(out, "oci:fingerprint", env.get("OCI_FINGERPRINT"));
                put(out, "oci:privateKey", env.get("OCI_PRIVATE_KEY"));
                put(out, "oci:region", env.get("OCI_REGION"));
            },
            "digitalocean", (env, out) -> {
                String tok = env.get("DIGITALOCEAN_TOKEN");
                if (tok == null || tok.isBlank()) tok = env.get("DIGITALOCEAN_ACCESS_TOKEN");
                put(out, "digitalocean:token", tok);
            });

    /**
     * Provider 별 env var → Pulumi config key 변환. 알려진 mapping 만 추출 — unknown key 는 silent
     * skip (안전 default). 모든 value 는 secret 으로 취급필요.
     *
     * @param provider CSP 이름 (canonical — aws/gcp/azure/...)
     * @param env credential 의 env map (e.g. {AWS_ACCESS_KEY_ID=AKIA..., AWS_SECRET_ACCESS_KEY=...})
     * @return Pulumi config key → value (e.g. {aws:accessKey=AKIA..., aws:secretKey=...})
     */
    public static Map<String, String> toPulumiConfig(String provider, Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null || env.isEmpty() || provider == null) return out;
        BiConsumer<Map<String, String>, Map<String, String>> mapper = MAPPERS.get(provider.toLowerCase());
        if (mapper != null) mapper.accept(env, out);
        return out;
    }

    /**
     * Process environment 에 inject 되면 안 되는 CSP-specific env 의 union. Pulumi binary 가 보면
     * default chain 으로 잡아채서 state backend 자격증명을 덮어씀.
     *
     * <p>State backend (RustFS) 가 사용하는 표준 AWS_* env 도 여기 포함 — host 의 system env
     * (compose env_file 같은 process-level) 에서 별도 set 필요.
     */
    private static final Set<String> CSP_ENV_BLOCKLIST = Set.of(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "GOOGLE_CREDENTIALS", "GOOGLE_APPLICATION_CREDENTIALS",
            "ARM_CLIENT_ID", "ARM_CLIENT_SECRET", "ARM_SUBSCRIPTION_ID", "ARM_TENANT_ID",
            "ALICLOUD_ACCESS_KEY", "ALICLOUD_SECRET_KEY",
            "OS_AUTH_URL", "OS_USERNAME", "OS_PASSWORD", "OS_PROJECT_NAME",
            "OS_REGION_NAME", "OS_USER_DOMAIN_NAME", "OS_PROJECT_DOMAIN_NAME",
            "OCI_TENANCY_OCID", "OCI_USER_OCID", "OCI_FINGERPRINT", "OCI_PRIVATE_KEY", "OCI_REGION",
            "DIGITALOCEAN_TOKEN", "DIGITALOCEAN_ACCESS_TOKEN");

    /**
     * CSP 자격증명 env 를 모두 제거 — caller 가 state backend (RustFS) 자격증명만 남긴 상태로
     * Pulumi binary 에 전달하기 위함.
     */
    public static Map<String, String> stripCspEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!CSP_ENV_BLOCKLIST.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
