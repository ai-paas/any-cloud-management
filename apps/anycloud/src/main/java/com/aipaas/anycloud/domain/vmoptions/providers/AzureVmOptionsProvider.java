package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class AzureVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final String ARM_SCOPE = "https://management.azure.com/.default";
    private static final String RESOURCE_SKUS_API_VERSION = "2021-07-01";
    private static final String VM_IMAGES_API_VERSION = "2024-11-01";
    private static final List<AzureImageReference> DEFAULT_IMAGES = List.of(
            new AzureImageReference("Canonical", "ubuntu-24_04-lts", "server", "linux"),
            new AzureImageReference("Canonical", "0001-com-ubuntu-server-jammy", "22_04-lts", "linux"),
            new AzureImageReference("Canonical", "0001-com-ubuntu-server-focal", "20_04-lts", "linux"),
            new AzureImageReference("RedHat", "RHEL", "9_3", "linux"),
            new AzureImageReference(
                    "MicrosoftWindowsServer", "WindowsServer", "2022-datacenter-azure-edition", "windows"));

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.AZURE;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "ARM Resource SKU와 VM Image API 기반으로 리전, VM 스펙, 대표 OS 이미지를 실시간 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        Map<String, VmOptionRegion> unique = new LinkedHashMap<>();
        for (AzureRecords.ResourceSku sku : listAllResourceSkus(null)) {
            if (!"virtualMachines".equalsIgnoreCase(sku.resourceType())) {
                continue;
            }
            if (sku.locations() == null) {
                continue;
            }
            for (String region : sku.locations()) {
                if (!StringUtils.hasText(region)) {
                    continue;
                }
                unique.putIfAbsent(
                        region.toLowerCase(Locale.ROOT),
                        VmOptionRegion.builder()
                                .provider(getProvider().getCanonicalName())
                                .id(region)
                                .name(region)
                                .available(true)
                                .build());
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        if (!StringUtils.hasText(region)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "Azure region is required");
        }

        List<VmOptionSpec> results = new ArrayList<>();
        for (AzureRecords.ResourceSku sku : listAllResourceSkus(region)) {
            if (!"virtualMachines".equalsIgnoreCase(sku.resourceType())) {
                continue;
            }
            if (!matchesKeyword(sku.name(), keyword)) {
                continue;
            }
            VmOptionSpec dto = toSpecDto(region, sku);
            if (gpuOnly && Optional.ofNullable(dto.getGpuCount()).orElse(0) <= 0) {
                continue;
            }
            results.add(dto);
            if (results.size() >= limit) {
                break;
            }
        }

        return results.stream()
                .sorted(Comparator.comparing(VmOptionSpec::getName))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        if (!StringUtils.hasText(region)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "Azure region is required");
        }

        List<AzureImageReference> candidates = resolveImageReferences(owner);
        List<VmOptionImage> images = new ArrayList<>();
        for (AzureImageReference reference : candidates) {
            try {
                for (AzureRecords.VmImageVersion version : listImageVersions(region, reference)) {
                    String versionName = version.name();
                    String imageName = "%s:%s:%s:%s"
                            .formatted(reference.publisher(), reference.offer(), reference.sku(), versionName);
                    if (!matchesKeyword(imageName, keyword) && !matchesKeyword(reference.offer(), keyword)) {
                        continue;
                    }
                    String inferredArchitecture = inferArchitecture(reference.sku());
                    if (StringUtils.hasText(architecture)
                            && !architecture.equalsIgnoreCase(
                                    Optional.ofNullable(inferredArchitecture).orElse(""))) {
                        continue;
                    }
                    images.add(VmOptionImage.builder()
                            .provider(getProvider().getCanonicalName())
                            .region(region)
                            .id(version.id() != null ? version.id() : imageName)
                            .name(imageName)
                            .osType(reference.osType())
                            .osVersion(inferOsVersion(reference.offer(), reference.sku()))
                            .architecture(inferredArchitecture)
                            .owner(reference.publisher())
                            .visibility("public")
                            .createdAt(versionName)
                            .build());
                    if (images.size() >= limit) {
                        return sortImages(images);
                    }
                }
            } catch (CustomException ignored) {
                // Some image references are not available in every region. Skip unavailable targets.
            }
        }
        return sortImages(images);
    }

    private List<VmOptionImage> sortImages(List<VmOptionImage> images) {
        return images.stream()
                .sorted(Comparator.comparing(VmOptionImage::getName))
                .toList();
    }

    private List<AzureRecords.ResourceSku> listAllResourceSkus(String regionFilter) {
        List<AzureRecords.ResourceSku> items = new ArrayList<>();
        String url = "https://management.azure.com/subscriptions/" + subscriptionId()
                + "/providers/Microsoft.Compute/skus?api-version=" + RESOURCE_SKUS_API_VERSION;
        if (StringUtils.hasText(regionFilter)) {
            url += "&$filter=" + encode("location eq '" + regionFilter + "'");
        }

        while (StringUtils.hasText(url)) {
            AzureRecords.SkuListResponse body = parseBody(
                    exchange(url, HttpMethod.GET, null).getBody(),
                    "azure-resource-skus",
                    AzureRecords.SkuListResponse.class);
            if (body.value() != null) {
                items.addAll(body.value());
            }
            url = body.nextLink();
        }
        return items;
    }

    private List<AzureRecords.VmImageVersion> listImageVersions(String region, AzureImageReference ref) {
        String url = "https://management.azure.com/subscriptions/" + subscriptionId()
                + "/providers/Microsoft.Compute/locations/" + region
                + "/publishers/" + ref.publisher()
                + "/artifacttypes/vmimage/offers/" + ref.offer()
                + "/skus/" + ref.sku()
                + "/versions?api-version=" + VM_IMAGES_API_VERSION
                + "&$top=5";
        AzureRecords.VmImageListResponse body = parseBody(
                exchange(url, HttpMethod.GET, null).getBody(),
                "azure-vm-images",
                AzureRecords.VmImageListResponse.class);
        List<AzureRecords.VmImageVersion> versions = new ArrayList<>(body.value() == null ? List.of() : body.value());
        versions.sort((a, b) -> Optional.ofNullable(b.name())
                .orElse("")
                .compareTo(Optional.ofNullable(a.name()).orElse("")));
        return versions;
    }

    private VmOptionSpec toSpecDto(String region, AzureRecords.ResourceSku sku) {
        Map<String, String> capabilities = capabilitiesOf(sku.capabilities());
        String name = sku.name();
        Integer gpuCount = parseInteger(capabilities.get("GPUs"));
        if (gpuCount == null) {
            gpuCount = inferGpuCount(name);
        }
        return VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(name)
                .name(name)
                .family(sku.family())
                .vcpu(parseInteger(capabilities.get("vCPUs")))
                .memoryGb(parseDouble(capabilities.get("MemoryGB")))
                .gpuCount(gpuCount)
                .architecture(capabilities.get("CpuArchitectureType"))
                .description("tier=" + (sku.tier() == null ? "" : sku.tier()))
                .available(true)
                .build();
    }

    private Map<String, String> capabilitiesOf(List<AzureRecords.Capability> capabilitiesList) {
        Map<String, String> capabilities = new LinkedHashMap<>();
        if (capabilitiesList == null) {
            return capabilities;
        }
        for (AzureRecords.Capability cap : capabilitiesList) {
            if (StringUtils.hasText(cap.name()) && StringUtils.hasText(cap.value())) {
                capabilities.put(cap.name(), cap.value());
            }
        }
        return capabilities;
    }

    private ResponseEntity<String> exchange(String url, HttpMethod method, HttpEntity<?> entity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        if (entity != null && entity.getHeaders() != null) {
            headers.addAll(entity.getHeaders());
        }
        Object body = entity == null ? null : entity.getBody();
        try {
            return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "azure",
                    url,
                    "Azure VM options request failed: " + e.getStatusCode().value() + " "
                            + e.getResponseBodyAsString());
        }
    }

    private <T> T parseBody(String body, String source, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, source, null, "Failed to parse Azure response: " + e.getMessage());
        }
    }

    private String accessToken() {
        String tenantId = requiredEnv("ARM_TENANT_ID");
        String clientId = requiredEnv("ARM_CLIENT_ID");
        String clientSecret = requiredEnv("ARM_CLIENT_SECRET");
        String body = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&scope=" + encode(ARM_SCOPE);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String url = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            AzureRecords.AccessTokenResponse tokenResp =
                    parseBody(response.getBody(), "azure-token", AzureRecords.AccessTokenResponse.class);
            String token = tokenResp.accessToken();
            if (!StringUtils.hasText(token)) {
                throw new CustomException(
                        ErrorCode.RUNTIME_EXCEPTION, "azure", null, "Azure access token was not created");
            }
            return token;
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "azure",
                    url,
                    "Failed to request Azure access token: " + e.getStatusCode().value());
        }
    }

    private String subscriptionId() {
        return requiredEnv("ARM_SUBSCRIPTION_ID");
    }

    private String requiredEnv(String key) {
        String value = resolveCredential(key);
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    key,
                    value,
                    key + " environment variable is required for Azure VM options");
        }
        return value;
    }

    private List<AzureImageReference> resolveImageReferences(String owner) {
        if (!StringUtils.hasText(owner)) {
            return DEFAULT_IMAGES;
        }
        String normalizedOwner = owner.toLowerCase(Locale.ROOT);
        return DEFAULT_IMAGES.stream()
                .filter(ref -> ref.publisher().toLowerCase(Locale.ROOT).contains(normalizedOwner))
                .toList();
    }

    private Integer parseInteger(String value) {
        try {
            return StringUtils.hasText(value) ? Integer.parseInt(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return StringUtils.hasText(value) ? Double.parseDouble(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer inferGpuCount(String skuName) {
        if (!StringUtils.hasText(skuName)) {
            return 0;
        }
        String normalized = skuName.toLowerCase(Locale.ROOT);
        return normalized.contains("_nc") || normalized.contains("_nd") || normalized.contains("_nv") ? 1 : 0;
    }

    private String inferArchitecture(String sku) {
        if (!StringUtils.hasText(sku)) {
            return null;
        }
        String normalized = sku.toLowerCase(Locale.ROOT);
        return normalized.contains("arm64") ? "arm64" : "x86_64";
    }

    private String inferOsVersion(String offer, String sku) {
        String joined = (offer + ":" + sku).toLowerCase(Locale.ROOT);
        if (joined.contains("24_04")) {
            return "24.04";
        }
        if (joined.contains("22_04")) {
            return "22.04";
        }
        if (joined.contains("20_04")) {
            return "20.04";
        }
        if (joined.contains("2022")) {
            return "2022";
        }
        if (joined.contains("9_3") || joined.contains("9")) {
            return "9";
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AzureImageReference(String publisher, String offer, String sku, String osType) {}

    // =================== Circuit breaker fallbacks ===================
    // resilience4j 의 @CircuitBreaker 가 OPEN 또는 record-exception 발생 시 호출.
    // 같은 인자 + 마지막 자리에 Throwable. 빈 list 반환으로 UI 부분 가용성 유지.

    @SuppressWarnings("unused")
    private List<VmOptionRegion> listRegionsFallback(Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionSpec> listSpecsFallback(
            String region, String keyword, boolean gpuOnly, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }
}
