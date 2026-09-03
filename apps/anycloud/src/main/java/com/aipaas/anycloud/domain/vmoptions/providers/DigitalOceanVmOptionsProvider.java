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
public class DigitalOceanVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final String API_BASE_URL = "https://api.digitalocean.com/v2";

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.DIGITALOCEAN;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "DigitalOcean API 기반으로 region, Droplet size, public image를 실시간 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        DigitalOceanRecords.RegionsResponse body = parseBody(
                exchange("/regions").getBody(), "digitalocean-regions", DigitalOceanRecords.RegionsResponse.class);
        List<DigitalOceanRecords.Region> regions = body.regions() == null ? List.of() : body.regions();
        List<VmOptionRegion> results = new ArrayList<>();
        for (DigitalOceanRecords.Region region : regions) {
            if (!Boolean.TRUE.equals(region.available())) {
                continue;
            }
            results.add(VmOptionRegion.builder()
                    .provider(getProvider().getCanonicalName())
                    .id(region.slug())
                    .name(region.name() != null ? region.name() : region.slug())
                    .available(true)
                    .build());
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        DigitalOceanRecords.SizesResponse body = parseBody(
                exchange("/sizes?per_page=200").getBody(),
                "digitalocean-sizes",
                DigitalOceanRecords.SizesResponse.class);
        List<DigitalOceanRecords.Size> sizes = body.sizes() == null ? List.of() : body.sizes();
        List<VmOptionSpec> results = new ArrayList<>();
        for (DigitalOceanRecords.Size size : sizes) {
            if (!matchesKeyword(size.slug(), keyword) && !matchesKeyword(size.description(), keyword)) {
                continue;
            }
            if (StringUtils.hasText(region) && !containsRegion(size.regions(), region)) {
                continue;
            }
            Integer gpuCount = inferGpuCount(size.slug());
            if (gpuOnly && Optional.ofNullable(gpuCount).orElse(0) <= 0) {
                continue;
            }
            Double memoryGb = size.memory() == null ? null : size.memory() / 1024.0;
            results.add(VmOptionSpec.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(region)
                    .id(size.slug())
                    .name(size.slug())
                    .family(inferFamily(size.slug()))
                    .vcpu(size.vcpus())
                    .memoryGb(memoryGb)
                    .gpuCount(gpuCount)
                    .architecture("x86_64")
                    .description(size.description())
                    .available(size.available() == null ? true : size.available())
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
        DigitalOceanRecords.ImagesResponse body = parseBody(
                exchange("/images?type=distribution&per_page=200").getBody(),
                "digitalocean-images",
                DigitalOceanRecords.ImagesResponse.class);
        List<DigitalOceanRecords.Image> images = body.images() == null ? List.of() : body.images();
        List<VmOptionImage> results = new ArrayList<>();
        for (DigitalOceanRecords.Image image : images) {
            String lookupName = StringUtils.hasText(image.slug()) ? image.slug() : image.name();
            if (!matchesKeyword(lookupName, keyword)
                    && !matchesKeyword(image.name(), keyword)
                    && !matchesKeyword(image.distribution(), keyword)) {
                continue;
            }
            String imageOwner = image.distribution() != null ? image.distribution() : "digitalocean";
            if (StringUtils.hasText(owner) && !owner.equalsIgnoreCase(imageOwner)) {
                continue;
            }
            String imageArchitecture = inferArchitecture(lookupName);
            if (StringUtils.hasText(architecture)
                    && !architecture.equalsIgnoreCase(
                            Optional.ofNullable(imageArchitecture).orElse(""))) {
                continue;
            }
            if (StringUtils.hasText(region) && !containsRegion(image.regions(), region)) {
                continue;
            }
            results.add(VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(region)
                    .id(image.id() != null ? image.id() : image.slug())
                    .name(lookupName)
                    .osType(inferOsType(image.name(), image.slug()))
                    .osVersion(inferOsVersion(image.name(), image.slug()))
                    .architecture(imageArchitecture)
                    .owner(imageOwner)
                    .visibility(Boolean.FALSE.equals(image.isPublic()) ? "private" : "public")
                    .createdAt(image.createdAt())
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionImage::getName))
                .toList();
    }

    private ResponseEntity<String> exchange(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken());
        try {
            return restTemplate.exchange(API_BASE_URL + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "digitalocean",
                    path,
                    "DigitalOcean VM options request failed: "
                            + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    private <T> T parseBody(String body, String source, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "response",
                    source,
                    "Failed to parse DigitalOcean VM options response: " + e.getMessage());
        }
    }

    private String accessToken() {
        String token = resolveCredential("DIGITALOCEAN_TOKEN");
        if (!StringUtils.hasText(token)) {
            token = resolveCredential("DIGITALOCEAN_ACCESS_TOKEN");
        }
        if (!StringUtils.hasText(token)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "DIGITALOCEAN_TOKEN",
                    null,
                    "DIGITALOCEAN_TOKEN or DIGITALOCEAN_ACCESS_TOKEN is required for DigitalOcean VM options");
        }
        return token;
    }

    private boolean containsRegion(List<String> regions, String region) {
        if (regions == null) {
            return false;
        }
        for (String item : regions) {
            if (region.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    private String inferFamily(String slug) {
        if (!StringUtils.hasText(slug)) {
            return null;
        }
        if (slug.startsWith("g-")) {
            return "general-purpose";
        }
        if (slug.startsWith("gd-")) {
            return "general-purpose-storage";
        }
        if (slug.startsWith("c-")) {
            return "cpu-optimized";
        }
        if (slug.startsWith("m-")) {
            return "memory-optimized";
        }
        if (slug.startsWith("so-")) {
            return "storage-optimized";
        }
        if (slug.startsWith("gpu-")) {
            return "gpu-optimized";
        }
        return "basic";
    }

    private Integer inferGpuCount(String slug) {
        if (!StringUtils.hasText(slug)) {
            return 0;
        }
        String normalized = slug.toLowerCase(Locale.ROOT);
        return normalized.startsWith("gpu-") ? 1 : 0;
    }

    private String inferArchitecture(String value) {
        if (!StringUtils.hasText(value)) {
            return "x86_64";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("arm64") || normalized.contains("arm")) {
            return "arm64";
        }
        if (normalized.contains("x64") || normalized.contains("x86_64")) {
            return "x86_64";
        }
        return "x86_64";
    }

    private String inferOsType(String imageName, String imageSlug) {
        String normalized = (Optional.ofNullable(imageSlug).orElse("") + " "
                        + Optional.ofNullable(imageName).orElse(""))
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) {
            return "windows";
        }
        return "linux";
    }

    private String inferOsVersion(String imageName, String imageSlug) {
        String normalized = (Optional.ofNullable(imageSlug).orElse("") + " "
                        + Optional.ofNullable(imageName).orElse(""))
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("24-04") || normalized.contains("24.04")) {
            return "24.04";
        }
        if (normalized.contains("22-04") || normalized.contains("22.04")) {
            return "22.04";
        }
        if (normalized.contains("20-04") || normalized.contains("20.04")) {
            return "20.04";
        }
        if (normalized.contains("debian-12") || normalized.contains("debian 12")) {
            return "12";
        }
        return null;
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
