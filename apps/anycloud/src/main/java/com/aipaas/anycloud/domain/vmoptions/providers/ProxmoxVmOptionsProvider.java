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
public class ProxmoxVmOptionsProvider extends AbstractVmOptionsProvider {

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.PROXMOX;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(
                getProvider(), true, "Proxmox VE API 기반으로 node, template VM, node capacity를 조회하고 VM 옵션을 생성합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        return listRegions(Map.of());
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsCredFallback")
    public List<VmOptionRegion> listRegions(Map<String, String> credentials) {
        ProxmoxSession session = authenticate(credentials);
        ProxmoxRecords.NodesResponse body =
                exchange(session, "/cluster/resources?type=node", ProxmoxRecords.NodesResponse.class);
        List<ProxmoxRecords.Node> nodes = body.data() == null ? List.of() : body.data();
        List<VmOptionRegion> results = new ArrayList<>();
        for (ProxmoxRecords.Node node : nodes) {
            if (!StringUtils.hasText(node.node())) {
                continue;
            }
            String status = node.status() == null ? "online" : node.status();
            results.add(VmOptionRegion.builder()
                    .provider(getProvider().getCanonicalName())
                    .id(node.node())
                    .name(node.node())
                    .available("online".equalsIgnoreCase(status))
                    .build());
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        return listSpecs(Map.of(), region, keyword, gpuOnly, limit);
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsCredFallback")
    public List<VmOptionSpec> listSpecs(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit) {
        ProxmoxSession session = authenticate(credentials);
        String node = resolveNode(region, session);
        ProxmoxRecords.NodeStatusResponse statusBody =
                exchange(session, "/nodes/" + encode(node) + "/status", ProxmoxRecords.NodeStatusResponse.class);
        ProxmoxRecords.NodeStatus status = statusBody.data();
        int totalCpu = (status == null || status.cpuinfo() == null)
                ? 0
                : status.cpuinfo().path("cpus").asInt(0);
        double totalMemoryGb = (status == null || status.memory() == null)
                ? 0
                : status.memory().path("total").asDouble(0) / 1024.0 / 1024.0 / 1024.0;
        boolean gpuAvailable = detectGpu(session, node);

        List<VmOptionSpec> candidates = new ArrayList<>();
        addSpecIfPossible(
                candidates,
                node,
                "proxmox-standard-2x4",
                "standard",
                2,
                4.0,
                0,
                "Node capacity 기반 기본 사양",
                totalCpu,
                totalMemoryGb,
                true);
        addSpecIfPossible(
                candidates,
                node,
                "proxmox-standard-4x8",
                "standard",
                4,
                8.0,
                0,
                "조금 더 여유 있는 컨트롤 플레인",
                totalCpu,
                totalMemoryGb,
                true);
        addSpecIfPossible(
                candidates,
                node,
                "proxmox-standard-8x16",
                "standard",
                8,
                16.0,
                0,
                "대형 워커 또는 컨트롤 플레인",
                totalCpu,
                totalMemoryGb,
                true);
        if (gpuAvailable) {
            addSpecIfPossible(
                    candidates,
                    node,
                    "proxmox-gpu-8x32",
                    "gpu",
                    8,
                    32.0,
                    1,
                    "GPU 장착 노드 기반 후보",
                    totalCpu,
                    totalMemoryGb,
                    true);
        }

        return candidates.stream()
                .filter(spec -> matchesKeyword(spec.getName(), keyword) || matchesKeyword(spec.getFamily(), keyword))
                .filter(spec ->
                        !gpuOnly || Optional.ofNullable(spec.getGpuCount()).orElse(0) > 0)
                .limit(limit)
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        return listImages(Map.of(), region, keyword, architecture, owner, limit);
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesCredFallback")
    public List<VmOptionImage> listImages(
            Map<String, String> credentials,
            String region,
            String keyword,
            String architecture,
            String owner,
            int limit) {
        ProxmoxSession session = authenticate(credentials);
        String node = resolveNode(region, session);
        ProxmoxRecords.VmTemplatesResponse body =
                exchange(session, "/nodes/" + encode(node) + "/qemu", ProxmoxRecords.VmTemplatesResponse.class);
        List<ProxmoxRecords.VmTemplate> templates = body.data() == null ? List.of() : body.data();
        List<VmOptionImage> results = new ArrayList<>();
        for (ProxmoxRecords.VmTemplate template : templates) {
            if (template.template() == null || template.template() != 1) {
                continue;
            }
            if (!matchesKeyword(template.name(), keyword) && !matchesKeyword(template.vmid(), keyword)) {
                continue;
            }
            String imageOwner = "proxmox-template";
            if (StringUtils.hasText(owner) && !owner.equalsIgnoreCase(imageOwner)) {
                continue;
            }
            String imageArchitecture = inferArchitecture(template.name());
            if (StringUtils.hasText(architecture)
                    && !architecture.equalsIgnoreCase(
                            Optional.ofNullable(imageArchitecture).orElse(""))) {
                continue;
            }
            results.add(VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(node)
                    .id(template.vmid())
                    .name(template.name())
                    .osType(inferOsType(template.name()))
                    .osVersion(inferOsVersion(template.name()))
                    .architecture(imageArchitecture)
                    .owner(imageOwner)
                    .visibility("private")
                    .createdAt(null)
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionImage::getName))
                .toList();
    }

    private void addSpecIfPossible(
            List<VmOptionSpec> target,
            String node,
            String id,
            String family,
            int vcpu,
            double memoryGb,
            int gpuCount,
            String description,
            int totalCpu,
            double totalMemoryGb,
            boolean available) {
        if (totalCpu < vcpu || totalMemoryGb < memoryGb) {
            return;
        }
        target.add(VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(node)
                .id(id)
                .name(id)
                .family(family)
                .vcpu(vcpu)
                .memoryGb(memoryGb)
                .gpuCount(gpuCount)
                .architecture("x86_64")
                .description(description)
                .available(available)
                .build());
    }

    private ProxmoxSession authenticate() {
        return authenticate(Map.of());
    }

    private ProxmoxSession authenticate(Map<String, String> credentials) {
        String endpoint = requiredKey(credentials, "PROXMOX_VE_ENDPOINT");
        String username = requiredKey(credentials, "PROXMOX_VE_USERNAME");
        String password = requiredKey(credentials, "PROXMOX_VE_PASSWORD");

        // API Token 모드 감지 : username 에 '!' 가 있으면 "<user>@<realm>!<tokenId>" 형식 — Proxmox
        // 의 API token. /access/ticket POST 대신 Authorization: PVEAPIToken=<user>=<secret> 사용.
        // 로컬 user/password 보다 권장되는 인증 방식 (revocable, scoped, no MFA prompt).
        if (username.contains("!")) {
            String authHeader = "PVEAPIToken=" + username + "=" + password;
            return new ProxmoxSession(normalizeEndpoint(endpoint), null, null, authHeader);
        }

        // password 모드 — /access/ticket POST 으로 ticket + csrfPreventionToken 발급.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = "username=" + encode(username) + "&password=" + encode(password);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    normalizeEndpoint(endpoint) + "/access/ticket",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            ProxmoxRecords.AuthResponse authBody =
                    parseBody(response.getBody(), "proxmox-auth", ProxmoxRecords.AuthResponse.class);
            ProxmoxRecords.AuthResponse.Auth data = authBody.data();
            String ticket = data == null ? null : data.ticket();
            String csrfToken = data == null ? null : data.csrfPreventionToken();
            if (!StringUtils.hasText(ticket)) {
                throw new CustomException(
                        ErrorCode.RUNTIME_EXCEPTION, "proxmox-auth", endpoint, "Proxmox ticket was not returned");
            }
            return new ProxmoxSession(normalizeEndpoint(endpoint), ticket, csrfToken, null);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "proxmox-auth",
                    endpoint,
                    "Proxmox authentication failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    private <T> T exchange(ProxmoxSession session, String path, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (session.isApiToken()) {
            // API token : Authorization header 하나로 끝.
            headers.add(HttpHeaders.AUTHORIZATION, session.apiTokenAuth());
        } else {
            // password ticket : Cookie + (mutating 요청용) CSRFPreventionToken.
            headers.add(HttpHeaders.COOKIE, "PVEAuthCookie=" + session.ticket());
            if (StringUtils.hasText(session.csrfPreventionToken())) {
                headers.add("CSRFPreventionToken", session.csrfPreventionToken());
            }
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    session.baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseBody(response.getBody(), path, type);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "proxmox",
                    path,
                    "Proxmox VM options request failed: " + e.getStatusCode().value() + " "
                            + e.getResponseBodyAsString());
        }
    }

    private <T> T parseBody(String body, String source, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "proxmox-response",
                    source,
                    "Failed to parse Proxmox VM options response: " + e.getMessage());
        }
    }

    private String resolveNode(String region, ProxmoxSession session) {
        if (StringUtils.hasText(region)) {
            return region;
        }
        ProxmoxRecords.NodesResponse body =
                exchange(session, "/cluster/resources?type=node", ProxmoxRecords.NodesResponse.class);
        List<ProxmoxRecords.Node> nodes = body.data() == null ? List.of() : body.data();
        for (ProxmoxRecords.Node node : nodes) {
            if (StringUtils.hasText(node.node())) {
                return node.node();
            }
        }
        throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "Proxmox node(region) is required");
    }

    private boolean detectGpu(ProxmoxSession session, String node) {
        try {
            ProxmoxRecords.PciDevicesResponse body = exchange(
                    session, "/nodes/" + encode(node) + "/hardware/pci", ProxmoxRecords.PciDevicesResponse.class);
            List<ProxmoxRecords.PciDevice> devices = body.data() == null ? List.of() : body.data();
            for (ProxmoxRecords.PciDevice device : devices) {
                String deviceClass = Optional.ofNullable(device.deviceClass()).orElse("");
                String deviceName = Optional.ofNullable(device.deviceName()).orElse("");
                String vendorName = Optional.ofNullable(device.vendorName()).orElse("");
                String normalized = (deviceClass + " " + deviceName + " " + vendorName).toLowerCase(Locale.ROOT);
                if (normalized.contains("vga")
                        || normalized.contains("3d")
                        || normalized.contains("nvidia")
                        || normalized.contains("amd")
                        || normalized.contains("gpu")) {
                    return true;
                }
            }
        } catch (CustomException ignored) {
            return false;
        }
        return false;
    }

    private String inferArchitecture(String value) {
        if (!StringUtils.hasText(value)) {
            return "x86_64";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("arm64") || normalized.contains("aarch64")) {
            return "arm64";
        }
        return "x86_64";
    }

    private String inferOsType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("windows") ? "windows" : "linux";
    }

    private String inferOsVersion(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("24.04")) {
            return "24.04";
        }
        if (normalized.contains("22.04")) {
            return "22.04";
        }
        if (normalized.contains("20.04")) {
            return "20.04";
        }
        if (normalized.contains("debian-12") || normalized.contains("debian 12")) {
            return "12";
        }
        return null;
    }

    private String requiredEnv(String key) {
        return requiredKey(Map.of(), key);
    }

    /**
     * 사용자가 등록한 credential map 에서 먼저 찾고, 없으면 환경변수 fallback.
     * 둘 다 없으면 INVALID_INPUT_VALUE — credential 미선택 + env 미설정 케이스를 빠르게 알림.
     */
    private String requiredKey(Map<String, String> credentials, String key) {
        if (credentials != null) {
            String fromCred = credentials.get(key);
            if (StringUtils.hasText(fromCred)) {
                return fromCred;
            }
        }
        String value = System.getenv(key);
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, key, null, key + " is required for Proxmox VM options");
        }
        return value;
    }

    private String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (trimmed.endsWith("/api2/json")) {
            return trimmed;
        }
        return trimmed + "/api2/json";
    }

    private String encode(String value) {
        return URLEncoder.encode(Optional.ofNullable(value).orElse(""), StandardCharsets.UTF_8);
    }

    /**
     * Proxmox 인증 세션. 두 모드 지원:
     * <ul>
     *   <li>password 모드: ticket + csrfPreventionToken 사용 (PVEAuthCookie + CSRFPreventionToken).</li>
     *   <li>API token 모드: apiTokenAuth 만 설정 — {@code Authorization: PVEAPIToken=...} header 사용.</li>
     * </ul>
     */
    private record ProxmoxSession(String baseUrl, String ticket, String csrfPreventionToken, String apiTokenAuth) {
        boolean isApiToken() {
            return StringUtils.hasText(apiTokenAuth);
        }
    }

    // =================== Circuit breaker fallbacks ===================
    @SuppressWarnings("unused")
    private List<VmOptionRegion> listRegionsFallback(Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionRegion> listRegionsCredFallback(Map<String, String> credentials, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionSpec> listSpecsFallback(
            String region, String keyword, boolean gpuOnly, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionSpec> listSpecsCredFallback(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionImage> listImagesCredFallback(
            Map<String, String> credentials,
            String region,
            String keyword,
            String architecture,
            String owner,
            int limit,
            Throwable e) {
        return java.util.Collections.emptyList();
    }
}
