package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import java.util.List;
import java.util.Map;

public final class ProvisioningCredentialRules {

    private ProvisioningCredentialRules() {}

    public static void validateCredentials(SupportedProvisioningProvider provider, PulumiProperties pulumiProperties) {
        validateCredentialValues(provider, Map.of(), pulumiProperties);
    }

    public static void validateCredentialValues(
            SupportedProvisioningProvider provider, Map<String, String> providedCredentials) {
        validateCredentialValues(provider, providedCredentials, null);
    }

    public static List<String> requiredCredentialKeys(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case AWS -> List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");
            case GCP -> List.of("GOOGLE_CREDENTIALS", "GOOGLE_APPLICATION_CREDENTIALS");
            case AZURE -> List.of("ARM_CLIENT_ID", "ARM_CLIENT_SECRET", "ARM_SUBSCRIPTION_ID", "ARM_TENANT_ID");
            case ALIBABA -> List.of("ALICLOUD_ACCESS_KEY", "ALICLOUD_SECRET_KEY");
            case OPENSTACK -> List.of("OS_AUTH_URL", "OS_USERNAME", "OS_PASSWORD", "OS_PROJECT_NAME");
            case OCI -> List.of(
                    "TF_VAR_tenancy_ocid",
                    "TF_VAR_user_ocid",
                    "TF_VAR_fingerprint",
                    "TF_VAR_region",
                    "TF_VAR_private_key",
                    "TF_VAR_private_key_path");
            case DIGITALOCEAN -> List.of("DIGITALOCEAN_TOKEN", "DIGITALOCEAN_ACCESS_TOKEN");
        };
    }

    public static void validateCredentialValues(
            SupportedProvisioningProvider provider,
            Map<String, String> providedCredentials,
            PulumiProperties pulumiProperties) {
        List<String> requiredEnvVars = requiredCredentialKeys(provider);

        if (provider == SupportedProvisioningProvider.GCP) {
            boolean hasInline = hasCredentialValue("GOOGLE_CREDENTIALS", providedCredentials, pulumiProperties);
            boolean hasPath =
                    hasCredentialValue("GOOGLE_APPLICATION_CREDENTIALS", providedCredentials, pulumiProperties);
            if (!hasInline && !hasPath) {
                throw new CustomException(
                        "Missing required provisioning credential: GOOGLE_CREDENTIALS or GOOGLE_APPLICATION_CREDENTIALS",
                        ErrorCode.INVALID_INPUT_VALUE);
            }
            return;
        }

        if (provider == SupportedProvisioningProvider.OCI) {
            boolean hasInline = hasCredentialValue("TF_VAR_private_key", providedCredentials, pulumiProperties);
            boolean hasPath = hasCredentialValue("TF_VAR_private_key_path", providedCredentials, pulumiProperties);
            List<String> baseMissing =
                    List.of("TF_VAR_tenancy_ocid", "TF_VAR_user_ocid", "TF_VAR_fingerprint", "TF_VAR_region").stream()
                            .filter(var -> !hasCredentialValue(var, providedCredentials, pulumiProperties))
                            .toList();
            if (!baseMissing.isEmpty() || (!hasInline && !hasPath)) {
                String missingMessage = String.join(", ", baseMissing);
                if (!hasInline && !hasPath) {
                    missingMessage = missingMessage.isBlank()
                            ? "TF_VAR_private_key or TF_VAR_private_key_path"
                            : missingMessage + ", TF_VAR_private_key or TF_VAR_private_key_path";
                }
                throw new CustomException(
                        "Missing required provisioning credentials: " + missingMessage, ErrorCode.INVALID_INPUT_VALUE);
            }
            return;
        }

        if (provider == SupportedProvisioningProvider.DIGITALOCEAN) {
            boolean hasPrimary = hasCredentialValue("DIGITALOCEAN_TOKEN", providedCredentials, pulumiProperties);
            boolean hasAlt = hasCredentialValue("DIGITALOCEAN_ACCESS_TOKEN", providedCredentials, pulumiProperties);
            if (!hasPrimary && !hasAlt) {
                throw new CustomException(
                        "Missing required provisioning credential: DIGITALOCEAN_TOKEN or DIGITALOCEAN_ACCESS_TOKEN",
                        ErrorCode.INVALID_INPUT_VALUE);
            }
            return;
        }

        List<String> missing = requiredEnvVars.stream()
                .filter(var -> !hasCredentialValue(var, providedCredentials, pulumiProperties))
                .toList();

        if (!missing.isEmpty()) {
            throw new CustomException(
                    "Missing required provisioning credentials: " + String.join(", ", missing),
                    ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public static boolean hasEnvValue(String key, PulumiProperties pulumiProperties) {
        return hasCredentialValue(key, Map.of(), pulumiProperties);
    }

    public static boolean hasCredentialValue(
            String key, Map<String, String> providedCredentials, PulumiProperties pulumiProperties) {
        String inlineValue = providedCredentials.get(key);
        if (inlineValue != null && !inlineValue.isBlank()) {
            return true;
        }
        if (pulumiProperties == null) {
            return false;
        }
        String configured = pulumiProperties.getEnvironment().get(key);
        if (configured != null && !configured.isBlank()) {
            return true;
        }
        String actual = System.getenv(key);
        return actual != null && !actual.isBlank();
    }
}
