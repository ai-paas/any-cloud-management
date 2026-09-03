package io.aipaas.cluster.provisioning.program;

/**
 * Provider 이름 정규화 — caller 가 "AWS", "aws", "AmazonWebServices" 등으로 입력해도 단일 canonical
 * 토큰 ("aws") 으로 환원.
 */
public final class ProviderName {

    private ProviderName() {}

    public static String canonical(String provider) {
        if (provider == null) return "aws";
        String p = provider.trim().toLowerCase();
        return switch (p) {
            case "", "aws" -> "aws";
            case "gcp", "google", "googlecloud" -> "gcp";
            case "azure", "msazure" -> "azure";
            case "alibaba", "alicloud", "aliyun" -> "alibaba";
            case "openstack", "open-stack" -> "openstack";
            case "oci", "oracle", "oraclecloud", "oraclecloudinfrastructure" -> "oci";
            case "digitalocean", "digital-ocean", "do" -> "digitalocean";
            default -> p;
        };
    }
}
