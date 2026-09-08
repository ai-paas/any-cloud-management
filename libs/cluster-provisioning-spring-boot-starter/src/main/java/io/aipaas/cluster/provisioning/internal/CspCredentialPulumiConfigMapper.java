package io.aipaas.cluster.provisioning.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** CSP credential 의 env 변수 (예: AWS_ACCESS_KEY_ID) 를 Pulumi stack config key (예: aws:accessKey) 로 변환. */
public final class CspCredentialPulumiConfigMapper {

    private CspCredentialPulumiConfigMapper() {}

    /** Provider 별 env → config key 매퍼. 새 CSP 추가 시 entry 1줄만 추가. */
    private static final Map<String, BiConsumer<Map<String, String>, Map<String, String>>> MAPPERS = Map.of(
            "aws",
                    (env, out) -> {
                        put(out, "aws:accessKey", env.get("AWS_ACCESS_KEY_ID"));
                        put(out, "aws:secretKey", env.get("AWS_SECRET_ACCESS_KEY"));
                        put(out, "aws:token", env.get("AWS_SESSION_TOKEN"));
                    },
            "gcp",
                    (env, out) -> {
                        String inline = env.get("GOOGLE_CREDENTIALS");
                        put(
                                out,
                                "gcp:credentials",
                                (inline != null && !inline.isBlank())
                                        ? inline
                                        : env.get("GOOGLE_APPLICATION_CREDENTIALS"));
                    },
            "azure",
                    (env, out) -> {
                        put(out, "azure-native:clientId", env.get("ARM_CLIENT_ID"));
                        put(out, "azure-native:clientSecret", env.get("ARM_CLIENT_SECRET"));
                        put(out, "azure-native:subscriptionId", env.get("ARM_SUBSCRIPTION_ID"));
                        put(out, "azure-native:tenantId", env.get("ARM_TENANT_ID"));
                    },
            "alibaba",
                    (env, out) -> {
                        put(out, "alicloud:accessKey", env.get("ALICLOUD_ACCESS_KEY"));
                        put(out, "alicloud:secretKey", env.get("ALICLOUD_SECRET_KEY"));
                    },
            "openstack",
                    (env, out) -> {
                        put(out, "openstack:authUrl", env.get("OS_AUTH_URL"));
                        put(out, "openstack:userName", env.get("OS_USERNAME"));
                        put(out, "openstack:userId", env.get("OS_USER_ID"));
                        put(out, "openstack:password", env.get("OS_PASSWORD"));
                        put(out, "openstack:tenantName", firstOf(env, "OS_PROJECT_NAME", "OS_TENANT_NAME"));
                        put(out, "openstack:tenantId", firstOf(env, "OS_PROJECT_ID", "OS_TENANT_ID"));
                        // Keystone v3 는 domain scope 없이 인증을 거부한다. openrc 가 NAME 과 ID 중
                        // 어느 쪽을 내보낼지는 배포마다 달라 둘 다 받는다.
                        put(out, "openstack:userDomainName", env.get("OS_USER_DOMAIN_NAME"));
                        put(out, "openstack:userDomainId", env.get("OS_USER_DOMAIN_ID"));
                        put(out, "openstack:projectDomainName", env.get("OS_PROJECT_DOMAIN_NAME"));
                        put(out, "openstack:projectDomainId", env.get("OS_PROJECT_DOMAIN_ID"));
                        put(out, "openstack:domainName", env.get("OS_DOMAIN_NAME"));
                        put(out, "openstack:domainId", env.get("OS_DOMAIN_ID"));
                        // region / insecure / endpointType 은 provider 가 env 에서도 읽지만 그 env 는
                        // CSP_ENV_BLOCKLIST 로 제거되므로 config 로 넘기지 않으면 값이 사라진다.
                        put(out, "openstack:region", env.get("OS_REGION_NAME"));
                        put(out, "openstack:endpointType", env.get("OS_INTERFACE"));
                        put(out, "openstack:cacertFile", env.get("OS_CACERT"));
                        putBool(out, "openstack:insecure", env.get("OS_INSECURE"));
                        // 사설망 배포는 Keystone 카탈로그가 외부에서 못 닿는 주소를 광고한다.
                        // override 없이는 "dial tcp 192.168.10.10:9292: network is unreachable".
                        putJsonObject(out, "openstack:endpointOverrides", env.get("OS_ENDPOINT_OVERRIDES"));
                    },
            "oci",
                    (env, out) -> {
                        put(out, "oci:tenancyOcid", env.get("OCI_TENANCY_OCID"));
                        put(out, "oci:userOcid", env.get("OCI_USER_OCID"));
                        put(out, "oci:fingerprint", env.get("OCI_FINGERPRINT"));
                        put(out, "oci:privateKey", env.get("OCI_PRIVATE_KEY"));
                        put(out, "oci:region", env.get("OCI_REGION"));
                    },
            "digitalocean",
                    (env, out) -> {
                        String tok = env.get("DIGITALOCEAN_TOKEN");
                        if (tok == null || tok.isBlank()) tok = env.get("DIGITALOCEAN_ACCESS_TOKEN");
                        put(out, "digitalocean:token", tok);
                    },
            "ibm",
                    (env, out) -> {
                        put(out, "ibm:ibmcloudApiKey", env.get("IBMCLOUD_API_KEY"));
                        put(out, "ibm:region", env.get("IBMCLOUD_REGION"));
                    },
            "proxmox",
                    (env, out) -> {
                        put(out, "proxmoxve:endpoint", env.get("PROXMOX_VE_ENDPOINT"));
                        // apiToken 과 username/password 는 배타적이다. 둘 다 넘기면 provider 가 거부한다.
                        String token = env.get("PROXMOX_VE_API_TOKEN");
                        if (token != null && !token.isBlank()) {
                            put(out, "proxmoxve:apiToken", token);
                        } else {
                            put(out, "proxmoxve:username", env.get("PROXMOX_VE_USERNAME"));
                            put(out, "proxmoxve:password", env.get("PROXMOX_VE_PASSWORD"));
                        }
                        putBool(out, "proxmoxve:insecure", env.get("PROXMOX_VE_INSECURE"));
                        // cloud-init 스니펫은 API 가 아니라 SSH 로 올라간다. API 토큰만 주면
                        // VM 은 만들어지고 user-data 업로드에서 죽는다.
                        put(out, "proxmoxve:ssh.username", env.get("PROXMOX_VE_SSH_USERNAME"));
                        put(out, "proxmoxve:ssh.password", env.get("PROXMOX_VE_SSH_PASSWORD"));
                        put(out, "proxmoxve:ssh.privateKey", env.get("PROXMOX_VE_SSH_PRIVATE_KEY"));
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

    /** Process environment 에 inject 되면 안 되는 CSP-specific env 의 union. */
    private static final Set<String> CSP_ENV_BLOCKLIST = Set.of(
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN",
            "GOOGLE_CREDENTIALS",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "ARM_CLIENT_ID",
            "ARM_CLIENT_SECRET",
            "ARM_SUBSCRIPTION_ID",
            "ARM_TENANT_ID",
            "IBMCLOUD_API_KEY",
            "IBMCLOUD_REGION",
            "PROXMOX_VE_ENDPOINT",
            "PROXMOX_VE_USERNAME",
            "PROXMOX_VE_PASSWORD",
            "PROXMOX_VE_API_TOKEN",
            "PROXMOX_VE_INSECURE",
            "PROXMOX_VE_SSH_USERNAME",
            "PROXMOX_VE_SSH_PASSWORD",
            "PROXMOX_VE_SSH_PRIVATE_KEY",
            "ALICLOUD_ACCESS_KEY",
            "ALICLOUD_SECRET_KEY",
            "OS_AUTH_URL",
            "OS_USERNAME",
            "OS_PASSWORD",
            "OS_PROJECT_NAME",
            "OS_REGION_NAME",
            "OS_USER_DOMAIN_NAME",
            "OS_PROJECT_DOMAIN_NAME",
            "OS_USER_ID",
            "OS_PROJECT_ID",
            "OS_TENANT_ID",
            "OS_TENANT_NAME",
            "OS_USER_DOMAIN_ID",
            "OS_PROJECT_DOMAIN_ID",
            "OS_DOMAIN_NAME",
            "OS_DOMAIN_ID",
            "OS_INTERFACE",
            "OS_CACERT",
            "OS_INSECURE",
            "OS_ENDPOINT_OVERRIDES",
            "OCI_TENANCY_OCID",
            "OCI_USER_OCID",
            "OCI_FINGERPRINT",
            "OCI_PRIVATE_KEY",
            "OCI_REGION",
            "DIGITALOCEAN_TOKEN",
            "DIGITALOCEAN_ACCESS_TOKEN");

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

    /** 같은 값을 두 이름으로 부르는 CSP 대응 (OpenStack 의 project/tenant). 앞선 이름이 우선. */
    private static String firstOf(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /** boolean config 는 Pulumi 가 문자열로 파싱한다. 인식 못 하는 값은 넘기지 않는다. */
    private static void putBool(Map<String, String> out, String key, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (TRUTHY.contains(normalized)) out.put(key, "true");
        else if (FALSY.contains(normalized)) out.put(key, "false");
    }

    /**
     * map 타입 provider config 는 JSON 문자열로 넘긴다 — Pulumi 가 읽을 때 파싱한다.
     *
     * <p>깨진 값을 그대로 넘기면 provider 가 stack 전체를 거부하고, 그 시점 메시지로는 자격증명의
     * 어느 항목이 문제인지 알 수 없다. 등록 시점에 거른다.
     */
    private static void putJsonObject(Map<String, String> out, String key, String value) {
        if (value == null || value.isBlank()) return;
        String json = value.trim();
        try {
            if (!JSON.readTree(json).isObject()) {
                throw new IllegalArgumentException(key + " 는 JSON object 여야 한다: " + json);
            }
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException(key + " 가 올바른 JSON 이 아니다: " + json, e);
        }
        out.put(key, json);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final java.util.Set<String> TRUTHY = java.util.Set.of("true", "1", "yes", "on");
    private static final java.util.Set<String> FALSY = java.util.Set.of("false", "0", "no", "off");
}
