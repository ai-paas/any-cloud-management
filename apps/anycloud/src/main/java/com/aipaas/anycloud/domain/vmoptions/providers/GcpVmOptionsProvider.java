package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class GcpVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final String COMPUTE_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final List<String> DEFAULT_IMAGE_PROJECTS =
            List.of("ubuntu-os-cloud", "debian-cloud", "cos-cloud", "rocky-linux-cloud", "centos-cloud");

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.GCP;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "Compute Engine API 기반으로 리전, VM 스펙, 공개 OS 이미지를 실시간 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        List<GcpRecords.RegionOrZone> items = listItems(
                "https://compute.googleapis.com/compute/v1/projects/" + projectId() + "/regions",
                GcpRecords.RegionOrZone.class);
        List<VmOptionRegion> regions = new ArrayList<>();
        for (GcpRecords.RegionOrZone region : items) {
            regions.add(VmOptionRegion.builder()
                    .provider(getProvider().getCanonicalName())
                    .id(region.name())
                    .name(region.name())
                    .available("UP".equalsIgnoreCase(region.status()))
                    .build());
        }
        return regions.stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        if (!StringUtils.hasText(region)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "GCP region is required");
        }

        List<String> zones = listZones(region);
        Map<String, VmOptionSpec> unique = new LinkedHashMap<>();
        for (String zone : zones) {
            List<GcpRecords.MachineType> items = listItems(
                    "https://compute.googleapis.com/compute/v1/projects/" + projectId() + "/zones/" + zone
                            + "/machineTypes",
                    GcpRecords.MachineType.class);
            for (GcpRecords.MachineType machineType : items) {
                if (!matchesKeyword(machineType.name(), keyword)) {
                    continue;
                }
                VmOptionSpec dto = toSpecDto(region, machineType);
                if (gpuOnly && Optional.ofNullable(dto.getGpuCount()).orElse(0) <= 0) {
                    continue;
                }
                unique.putIfAbsent(dto.getId(), dto);
                if (unique.size() >= limit) {
                    return new ArrayList<>(unique.values());
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        List<VmOptionImage> images = new ArrayList<>();
        for (String imageProject : resolveImageProjects(owner)) {
            List<GcpRecords.Image> items = listItems(
                    "https://compute.googleapis.com/compute/v1/projects/" + imageProject + "/global/images",
                    GcpRecords.Image.class);
            for (GcpRecords.Image image : items) {
                if (!matchesKeyword(image.name(), keyword)) {
                    continue;
                }
                if (StringUtils.hasText(architecture)
                        && !architecture.equalsIgnoreCase(
                                Optional.ofNullable(image.architecture()).orElse(""))) {
                    continue;
                }
                if (image.deprecated() != null && !image.deprecated().isNull()) {
                    continue;
                }
                images.add(VmOptionImage.builder()
                        .provider(getProvider().getCanonicalName())
                        .region(region)
                        .id(image.selfLink() != null ? image.selfLink() : image.id())
                        .name(image.name())
                        .osType(inferOsType(image.name()))
                        .osVersion(inferOsVersion(image.name()))
                        .architecture(image.architecture())
                        .owner(imageProject)
                        .visibility("public")
                        .createdAt(image.creationTimestamp())
                        .build());
                if (images.size() >= limit) {
                    return sortImages(images);
                }
            }
        }
        return sortImages(images);
    }

    private List<VmOptionImage> sortImages(List<VmOptionImage> images) {
        return images.stream()
                .sorted(Comparator.comparing(VmOptionImage::getCreatedAt, Comparator.nullsLast(String::compareTo))
                        .reversed())
                .toList();
    }

    private List<String> listZones(String region) {
        List<GcpRecords.RegionOrZone> items = listItems(
                "https://compute.googleapis.com/compute/v1/projects/" + projectId() + "/zones",
                GcpRecords.RegionOrZone.class);
        List<String> zones = new ArrayList<>();
        for (GcpRecords.RegionOrZone zone : items) {
            if (!StringUtils.hasText(zone.name()) || !zone.name().startsWith(region + "-")) {
                continue;
            }
            if (!"UP".equalsIgnoreCase(zone.status())) {
                continue;
            }
            zones.add(zone.name());
        }
        if (zones.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, "region", region, "No active zones found for GCP region");
        }
        return zones;
    }

    private VmOptionSpec toSpecDto(String region, GcpRecords.MachineType mt) {
        Double memoryGb = mt.memoryMb() == null ? null : mt.memoryMb() / 1024.0;
        Integer gpuCount = inferGpuCount(mt.name());
        return VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(mt.name())
                .name(mt.name())
                .family(inferFamily(mt.name()))
                .vcpu(mt.guestCpus())
                .memoryGb(memoryGb)
                .gpuCount(gpuCount)
                .architecture(inferArchitecture(mt.name()))
                .description(mt.description())
                .available(true)
                .build();
    }

    /**
     * L1: GCP REST API 의 paginated list 응답 {@code {"items":[...], "nextPageToken":"..."}}
     * 의 items array 만 typed record 리스트로 변환. nextPageToken pagination 은 본 prototype 에선
     * 미적용 (필요 시 향후 별 PR).
     */
    private <T> List<T> listItems(String url, Class<T> type) {
        ResponseEntity<String> response = exchange(url);
        JsonNode root = parseBody(response.getBody());
        JsonNode items = root.path("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(items.size());
        for (JsonNode el : items) {
            out.add(objectMapper.convertValue(el, type));
        }
        return out;
    }

    private ResponseEntity<String> exchange(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parseBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "response",
                    "gcp",
                    "Failed to parse GCP VM options response: " + e.getMessage());
        }
    }

    private String projectId() {
        String explicit = resolveCredential("GOOGLE_CLOUD_PROJECT");
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String credentialsJson = resolveCredential("GOOGLE_CREDENTIALS");
        if (StringUtils.hasText(credentialsJson)) {
            return readProjectIdFromJson(credentialsJson);
        }
        String credentialsPath = resolveCredential("GOOGLE_APPLICATION_CREDENTIALS");
        if (StringUtils.hasText(credentialsPath)) {
            try {
                return readProjectIdFromJson(Files.readString(Path.of(credentialsPath)));
            } catch (IOException e) {
                throw new CustomException(
                        ErrorCode.RUNTIME_EXCEPTION,
                        "GOOGLE_APPLICATION_CREDENTIALS",
                        credentialsPath,
                        "Failed to read GCP credential file: " + e.getMessage());
            }
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                "gcpProject",
                null,
                "GOOGLE_CLOUD_PROJECT or service account project_id is required");
    }

    private String readProjectIdFromJson(String json) {
        try {
            GcpRecords.ServiceAccountJson sa = objectMapper.readValue(json, GcpRecords.ServiceAccountJson.class);
            String projectId = sa.projectId();
            if (!StringUtils.hasText(projectId)) {
                throw new CustomException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "project_id",
                        null,
                        "GCP service account json does not contain project_id");
            }
            return projectId;
        } catch (IOException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "GOOGLE_CREDENTIALS",
                    null,
                    "Failed to parse GCP credential json: " + e.getMessage());
        }
    }

    private String accessToken() {
        try {
            GoogleCredentials credentials = loadCredentials().createScoped(List.of(COMPUTE_SCOPE));
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null || !StringUtils.hasText(token.getTokenValue())) {
                throw new CustomException(ErrorCode.RUNTIME_EXCEPTION, "gcp", null, "GCP access token was not created");
            }
            return token.getTokenValue();
        } catch (IOException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, "gcp", null, "Failed to load GCP credentials: " + e.getMessage());
        }
    }

    private GoogleCredentials loadCredentials() throws IOException {
        String credentialsJson = resolveCredential("GOOGLE_CREDENTIALS");
        if (StringUtils.hasText(credentialsJson)) {
            return GoogleCredentials.fromStream(
                    new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        }
        String credentialsPath = resolveCredential("GOOGLE_APPLICATION_CREDENTIALS");
        if (StringUtils.hasText(credentialsPath)) {
            return GoogleCredentials.fromStream(Files.newInputStream(Path.of(credentialsPath)));
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                "gcp",
                null,
                "GOOGLE_CREDENTIALS or GOOGLE_APPLICATION_CREDENTIALS is required");
    }

    private List<String> resolveImageProjects(String owner) {
        if (!StringUtils.hasText(owner)) {
            return DEFAULT_IMAGE_PROJECTS;
        }
        return List.of(owner.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Integer inferGpuCount(String machineTypeName) {
        if (!StringUtils.hasText(machineTypeName)) {
            return 0;
        }
        String normalized = machineTypeName.toLowerCase(Locale.ROOT);
        return normalized.contains("a2") || normalized.contains("g2") ? 1 : 0;
    }

    private String inferFamily(String machineTypeName) {
        if (!StringUtils.hasText(machineTypeName)) {
            return null;
        }
        int separator = machineTypeName.indexOf('-');
        return separator > 0 ? machineTypeName.substring(0, separator) : machineTypeName;
    }

    private String inferArchitecture(String machineTypeName) {
        if (!StringUtils.hasText(machineTypeName)) {
            return null;
        }
        String normalized = machineTypeName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("t2a")) {
            return "arm64";
        }
        return "x86_64";
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
                || normalized.contains("debian")
                || normalized.contains("cos")
                || normalized.contains("centos")
                || normalized.contains("rocky")) {
            return "linux";
        }
        return "unknown";
    }

    private String inferOsVersion(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        for (String candidate : List.of("2404", "2204", "2004", "24.04", "22.04", "20.04")) {
            if (name.contains(candidate)) {
                return candidate
                        .replace("2404", "24.04")
                        .replace("2204", "22.04")
                        .replace("2004", "20.04");
            }
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
