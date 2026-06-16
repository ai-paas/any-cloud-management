package com.aipaas.anycloud.domain.provisioning.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum SupportedProvisioningProvider {
    AWS("AWS", List.of("aws")),
    GCP("GCP", List.of("gcp", "google", "googlecloud")),
    AZURE("Azure", List.of("azure", "msazure")),
    ALIBABA("Alibaba", List.of("alibaba", "alicloud", "aliyun")),
    OPENSTACK("OpenStack", List.of("openstack", "open-stack")),
    PROXMOX("Proxmox", List.of("proxmox", "proxmoxve", "pve")),
    OCI("OCI", List.of("oci", "oracle", "oraclecloud", "oraclecloudinfrastructure")),
    DIGITALOCEAN("DigitalOcean", List.of("digitalocean", "digital-ocean", "do"));

    private final String canonicalName;
    private final List<String> aliases;

    SupportedProvisioningProvider(String canonicalName, List<String> aliases) {
        this.canonicalName = canonicalName;
        this.aliases = aliases;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public static SupportedProvisioningProvider from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider is blank");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(provider -> provider.aliases.contains(normalized)
                        || provider.canonicalName.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider: " + value));
    }
}
