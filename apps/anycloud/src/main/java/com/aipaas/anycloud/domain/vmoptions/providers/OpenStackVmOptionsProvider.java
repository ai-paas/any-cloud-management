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
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class OpenStackVmOptionsProvider extends AbstractVmOptionsProvider {

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.OPENSTACK;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "Keystone/Nova/Glance API 기반으로 리전, VM flavor, OS 이미지를 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        OpenStackSession session = authenticate();
        return session.regions().stream()
                .map(region -> VmOptionRegion.builder()
                        .provider(getProvider().getCanonicalName())
                        .id(region)
                        .name(region)
                        .available(true)
                        .build())
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        OpenStackSession session = authenticate();
        String resolvedRegion = resolveRegion(region, session);
        ResponseEntity<String> response = exchange(
                session.computeEndpoint(resolvedRegion) + "/flavors/detail", session.token(), HttpMethod.GET, null);
        OpenStackRecords.FlavorsResponse body = parseBody(response.getBody(), OpenStackRecords.FlavorsResponse.class);
        List<OpenStackRecords.Flavor> flavors = body.flavors() == null ? List.of() : body.flavors();
        List<VmOptionSpec> results = new ArrayList<>();
        for (OpenStackRecords.Flavor flavor : flavors) {
            if (!matchesKeyword(flavor.name(), keyword)) {
                continue;
            }
            int gpuCount = detectGpuCount(flavor.name());
            if (gpuOnly && gpuCount <= 0) {
                continue;
            }
            Double memoryGb = flavor.ram() == null ? null : flavor.ram() / 1024.0;
            results.add(VmOptionSpec.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(flavor.id())
                    .name(flavor.name())
                    .family(inferFamily(flavor.name()))
                    .vcpu(flavor.vcpus())
                    .memoryGb(memoryGb)
                    .gpuCount(gpuCount)
                    .architecture(extractMetadata(flavor.extraSpecs(), "architecture"))
                    .description("disk=" + (flavor.disk() == null ? "?" : flavor.disk()) + "GB")
                    .available(true)
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        OpenStackSession session = authenticate();
        String resolvedRegion = resolveRegion(region, session);
        ResponseEntity<String> response =
                exchange(session.imageEndpoint(resolvedRegion) + "/v2/images", session.token(), HttpMethod.GET, null);
        OpenStackRecords.ImagesResponse body = parseBody(response.getBody(), OpenStackRecords.ImagesResponse.class);
        List<OpenStackRecords.Image> images = body.images() == null ? List.of() : body.images();
        List<VmOptionImage> results = new ArrayList<>();
        for (OpenStackRecords.Image image : images) {
            if (!matchesKeyword(image.name(), keyword)) {
                continue;
            }
            if (StringUtils.hasText(architecture)
                    && !architecture.equalsIgnoreCase(
                            Optional.ofNullable(image.architecture()).orElse(""))) {
                continue;
            }
            if (StringUtils.hasText(owner)
                    && !owner.equalsIgnoreCase(
                            Optional.ofNullable(image.owner()).orElse(""))) {
                continue;
            }
            results.add(VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(image.id())
                    .name(image.name())
                    .osType(inferOsType(image.name()))
                    .osVersion(inferOsVersion(image.name()))
                    .architecture(image.architecture())
                    .owner(image.owner())
                    .visibility(image.visibility())
                    .createdAt(image.createdAt())
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private OpenStackSession authenticate() {
        String authUrl = requiredEnv("OS_AUTH_URL");
        String username = requiredEnv("OS_USERNAME");
        String password = requiredEnv("OS_PASSWORD");
        String projectName = requiredEnv("OS_PROJECT_NAME");
        String userDomain = env("OS_USER_DOMAIN_NAME", "Default");
        String projectDomain = env("OS_PROJECT_DOMAIN_NAME", "Default");
        String preferredRegion = resolveCredential("OS_REGION_NAME");
        String interfaceName = env("OS_INTERFACE", "public");

        String payload =
                """
				{
				  "auth": {
				    "identity": {
				      "methods": ["password"],
				      "password": {
				        "user": {
				          "name": "%s",
				          "domain": { "name": "%s" },
				          "password": "%s"
				        }
				      }
				    },
				    "scope": {
				      "project": {
				        "name": "%s",
				        "domain": { "name": "%s" }
				      }
				    }
				  }
				}
				"""
                        .formatted(
                                escapeJson(username),
                                escapeJson(userDomain),
                                escapeJson(password),
                                escapeJson(projectName),
                                escapeJson(projectDomain));

        ResponseEntity<String> response =
                exchange(normalizeAuthUrl(authUrl) + "/auth/tokens", null, HttpMethod.POST, payload);
        String token = response.getHeaders().getFirst("X-Subject-Token");
        if (!StringUtils.hasText(token)) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, "openstack", authUrl, "OpenStack token was not returned");
        }
        OpenStackRecords.AuthResponse authBody = parseBody(response.getBody(), OpenStackRecords.AuthResponse.class);
        List<OpenStackRecords.Service> catalog =
                authBody.token() == null || authBody.token().catalog() == null
                        ? List.of()
                        : authBody.token().catalog();
        return new OpenStackSession(token, interfaceName, preferredRegion, catalog);
    }

    private ResponseEntity<String> exchange(String url, String token, HttpMethod method, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(token)) {
            headers.set("X-Auth-Token", token);
        }
        // OpenStack 일부 deployment 가 response Content-Type 에 charset 미지정 → Spring 의 default 디코딩으로
        // 한글 / multi-byte 문자 mojibake 위험. byte[] → 명시 UTF-8 변환 (RestTemplateUtf8 javadoc 참조).
        return com.aipaas.anycloud.common.util.RestTemplateUtf8.exchangeAsUtf8String(
                restTemplate, url, method, new HttpEntity<>(body, headers));
    }

    private <T> T parseBody(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "response",
                    "openstack",
                    "Failed to parse OpenStack VM options response: " + e.getMessage());
        }
    }

    private String resolveRegion(String region, OpenStackSession session) {
        if (StringUtils.hasText(region)) {
            return region;
        }
        return session.regions().stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(
                        ErrorCode.INVALID_INPUT_VALUE, "region", null, "OpenStack region is required"));
    }

    private String normalizeAuthUrl(String authUrl) {
        String trimmed = authUrl.endsWith("/") ? authUrl.substring(0, authUrl.length() - 1) : authUrl;
        if (trimmed.endsWith("/auth/tokens")) {
            return trimmed.substring(0, trimmed.length() - "/auth/tokens".length());
        }
        return trimmed;
    }

    private String requiredEnv(String key) {
        String value = resolveCredential(key);
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    key,
                    value,
                    key + " environment variable is required for OpenStack VM options");
        }
        return value;
    }

    private String env(String key, String defaultValue) {
        String value = resolveCredential(key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private int detectGpuCount(String flavorName) {
        if (!StringUtils.hasText(flavorName)) {
            return 0;
        }
        return flavorName.toLowerCase(Locale.ROOT).contains("gpu") ? 1 : 0;
    }

    private String inferFamily(String flavorName) {
        if (!StringUtils.hasText(flavorName)) {
            return null;
        }
        int separator = flavorName.indexOf('.');
        return separator > 0 ? flavorName.substring(0, separator) : flavorName;
    }

    private String extractMetadata(Map<String, String> extraSpecs, String key) {
        if (extraSpecs == null) {
            return null;
        }
        return extraSpecs.get(key);
    }

    private String inferOsType(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) {
            return "windows";
        }
        if (normalized.contains("ubuntu")
                || normalized.contains("rocky")
                || normalized.contains("centos")
                || normalized.contains("debian")
                || normalized.contains("rhel")) {
            return "linux";
        }
        return "unknown";
    }

    private String inferOsVersion(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        for (String candidate : List.of("24.04", "22.04", "20.04", "9", "8")) {
            if (name.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record OpenStackSession(
            String token, String iface, String preferredRegion, List<OpenStackRecords.Service> serviceDirectory) {

        List<String> regions() {
            List<String> collected = serviceDirectory.stream()
                    .flatMap(service -> service.endpoints() == null
                            ? java.util.stream.Stream.empty()
                            : service.endpoints().stream())
                    .map(OpenStackRecords.Endpoint::region)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .sorted()
                    .toList();
            if (!collected.isEmpty()) {
                return collected;
            }
            return StringUtils.hasText(preferredRegion) ? List.of(preferredRegion) : List.of();
        }

        String computeEndpoint(String region) {
            return serviceEndpoint("compute", region);
        }

        String imageEndpoint(String region) {
            return serviceEndpoint("image", region);
        }

        private String serviceEndpoint(String serviceType, String region) {
            return serviceDirectory.stream()
                    .filter(service -> serviceType.equalsIgnoreCase(service.type()))
                    .flatMap(service -> service.endpoints() == null
                            ? java.util.stream.Stream.empty()
                            : service.endpoints().stream())
                    .filter(endpoint -> iface.equalsIgnoreCase(endpoint.iface()))
                    .filter(endpoint -> !StringUtils.hasText(region) || region.equalsIgnoreCase(endpoint.region()))
                    .map(OpenStackRecords.Endpoint::url)
                    .findFirst()
                    .orElseThrow(() -> new CustomException(
                            ErrorCode.RUNTIME_EXCEPTION,
                            "serviceType",
                            serviceType,
                            "OpenStack service endpoint not found for region " + region));
        }
    }

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
