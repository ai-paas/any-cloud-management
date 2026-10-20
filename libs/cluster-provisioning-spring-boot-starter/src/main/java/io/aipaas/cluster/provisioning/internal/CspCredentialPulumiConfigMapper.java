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
                        // Keystone v3 는 domain scope 없이 인증 거부. openrc 의 NAME, ID 출력
                        // 여부가 배포별로 달라 양쪽 모두 수용.
                        put(out, "openstack:userDomainName", env.get("OS_USER_DOMAIN_NAME"));
                        put(out, "openstack:userDomainId", env.get("OS_USER_DOMAIN_ID"));
                        put(out, "openstack:projectDomainName", env.get("OS_PROJECT_DOMAIN_NAME"));
                        put(out, "openstack:projectDomainId", env.get("OS_PROJECT_DOMAIN_ID"));
                        put(out, "openstack:domainName", env.get("OS_DOMAIN_NAME"));
                        put(out, "openstack:domainId", env.get("OS_DOMAIN_ID"));
                        // region, insecure, endpointType 은 provider 가 env 에서도 읽으나 해당
                        // env 는 CSP_ENV_BLOCKLIST 로 제거되므로 config 미전달 시 값 유실.
                        put(out, "openstack:region", env.get("OS_REGION_NAME"));
                        put(out, "openstack:endpointType", env.get("OS_INTERFACE"));
                        put(out, "openstack:cacertFile", env.get("OS_CACERT"));
                        putBool(out, "openstack:insecure", env.get("OS_INSECURE"));
                        // 사설망 배포는 Keystone 카탈로그가 외부 접근 불가 주소를 광고.
                        // override 부재 시 "dial tcp 192.168.10.10:9292: network is unreachable".
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
                        put(out, "proxmoxve:apiToken", proxmoxApiToken(env));
                        putBool(out, "proxmoxve:insecure", env.get("PROXMOX_VE_INSECURE"));
                    });

    /**
     * PVE 토큰 생성 화면이 분리 표시하는 두 값을 provider 입력 형식의 한 줄로 결합.
     *
     * <p>{@code user@realm!name=uuid} 형식이라 사용자가 직접 조립하면 오타가 검증을 통과.
     * 실패는 프로비저닝 첫 API 호출의 401 로만 드러남.
     */
    private static String proxmoxApiToken(Map<String, String> env) {
        String id = env.get("PROXMOX_VE_API_TOKEN_ID");
        String secret = env.get("PROXMOX_VE_API_TOKEN_SECRET");
        if (id == null || id.isBlank() || secret == null || secret.isBlank()) return null;
        return id.trim() + "=" + secret.trim();
    }

    /**
     * Provider 별 env var → Pulumi config key 변환. 알려진 mapping 만 추출: unknown key 는 silent
     * skip (안전 default). 모든 value 는 secret 으로 취급필요.
     *
     * @param provider CSP 이름 (canonical: aws/gcp/azure/...)
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
            "PROXMOX_VE_API_TOKEN_ID",
            "PROXMOX_VE_API_TOKEN_SECRET",
            "PROXMOX_VE_INSECURE",
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
     * CSP 자격증명 env 를 모두 제거: caller 가 state backend (RustFS) 자격증명만 남긴 상태로
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

    /** boolean config 는 Pulumi 가 문자열로 파싱. 미인식 값은 전달 제외. */
    private static void putBool(Map<String, String> out, String key, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (TRUTHY.contains(normalized)) out.put(key, "true");
        else if (FALSY.contains(normalized)) out.put(key, "false");
    }

    /**
     * map 타입 provider config 는 JSON 문자열로 전달. 파싱은 Pulumi 가 수행.
     *
     * <p>손상된 값을 그대로 전달하면 provider 가 stack 전체를 거부하고, 해당 시점 메시지로는
     * 자격증명의 문제 항목 식별 불가. 등록 시점에 검증.
     *
     * <p>{@code [{"name":"pve1","address":"10.0.0.11"}]} 형태. 값 손상 시 SSH 목적지가 달라짐.
     */
    private static void putJsonArray(Map<String, String> out, String key, String value) {
        if (value == null || value.isBlank()) return;
        String json = value.trim();
        try {
            if (!JSON.readTree(json).isArray()) {
                throw new IllegalArgumentException(key + " 는 JSON array 여야 한다: " + json);
            }
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException(key + " 가 올바른 JSON 이 아니다: " + json, e);
        }
        out.put(key, json);
    }

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

    /**
     * 프로바이더의 리전 config 키. 리전 개념이 없는 프로바이더는 {@code null}.
     *
     * <p>Proxmox 는 단일 하이퍼바이저라 리전 부재. OpenStack 은 리소스별로 region 을 직접 수신.
     */
    public static String regionConfigKey(String provider) {
        if (provider == null) {
            return null;
        }
        return switch (provider.toLowerCase(java.util.Locale.ROOT)) {
            case "aws" -> "aws:region";
            case "gcp", "google" -> "gcp:region";
            case "azure", "azurerm" -> "azure:location";
            case "alibaba", "alicloud" -> "alicloud:region";
            case "oci", "oracle" -> "oci:region";
            case "digitalocean" -> null;
            case "ibm" -> "ibm:region";
            default -> null;
        };
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final java.util.Set<String> TRUTHY = java.util.Set.of("true", "1", "yes", "on");
    private static final java.util.Set<String> FALSY = java.util.Set.of("false", "0", "no", "off");
}
