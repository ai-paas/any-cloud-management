package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.CpuArchitecture;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenStackVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenStackVmOptionsProvider.class);

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

    /** 선택 목록 최대 개수. 카탈로그 규모가 크면 화면 사용성 저하. */
    private static final int OPTION_LIMIT = 200;

    /**
     * providerSpec 값도 카탈로그 조회로 제공.
     *
     * <p>이미지와 flavor 는 카탈로그 조회가 있으나 providerSpec 은 자유 입력이었음. 이름을 직접
     * 입력하면 오타가 프로비저닝 단계까지 드러나지 않음.
     */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsFallback")
    public List<String> listConfigOptions(String configKey, String region) {
        return switch (configKey) {
            case "providerSpec.imageName" -> listImages(region, null, null, null, OPTION_LIMIT).stream()
                    .map(VmOptionImage::getName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            case "providerSpec.flavorName" -> listSpecs(region, null, false, OPTION_LIMIT).stream()
                    .map(VmOptionSpec::getName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
                /*
                 * 라우터 게이트웨이와 floating IP 는 external 네트워크에만 연결 가능. 내부망
                 * 선택 시 라우터 생성 거절. 이름만으로는 external 여부 구분 불가.
                 */
            case "providerSpec.externalNetworkId" -> externalNetworks(region).stream()
                    .map(OpenStackRecords.Network::id)
                    .filter(StringUtils::hasText)
                    .toList();
                // 풀 이름은 external 네트워크 이름과 동일. 두 값을 따로 입력할 필요 없음.
            case "providerSpec.floatingIpPool" -> externalNetworks(region).stream()
                    .map(OpenStackRecords.Network::name)
                    .filter(StringUtils::hasText)
                    .toList();
            default -> List.of();
        };
    }

    /** 외부망은 UUID 로 지정. 이름 미동반 시 화면에서 망 식별 불가. */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsWithLabelsFallback")
    public List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabels(
            Map<String, String> credentials, String configKey, String region) {
        if (!"providerSpec.externalNetworkId".equals(configKey)) {
            return super.listConfigOptionsWithLabels(credentials, configKey, region);
        }
        return withCredentials(credentials, () -> externalNetworks(region).stream()
                .filter(n -> StringUtils.hasText(n.id()))
                .map(n -> new com.aipaas.anycloud.domain.vmoptions.api.ConfigOption(
                        n.id(), StringUtils.hasText(n.name()) ? n.name() : n.id()))
                .toList());
    }

    private List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabelsFallback(
            Map<String, String> credentials, String configKey, String region, Throwable throwable) {
        LOG.warn("OpenStack config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        // 조회 실패 시 자유 입력으로 유지. 목록 제공 실패가 생성 차단 사유는 아님.
        LOG.warn("OpenStack config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    /**
     * Neutron 카탈로그 주소의 {@code /v2.0} 포함 여부는 배포별 상이. 그대로 결합하면
     * {@code /v2.0/v2.0/networks} 가 되어 404 응답.
     */
    static String neutronUrl(String endpoint, String path) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base.endsWith("/v2.0") ? base + path : base + "/v2.0" + path;
    }

    private List<OpenStackRecords.Network> externalNetworks(String region) {
        OpenStackSession session = authenticate();
        String resolvedRegion = resolveRegion(region, session);
        ResponseEntity<String> response = exchange(
                neutronUrl(session.networkEndpoint(resolvedRegion), "/networks?router:external=true"),
                session.token(),
                HttpMethod.GET,
                null);
        OpenStackRecords.NetworksResponse body = parseBody(response.getBody(), OpenStackRecords.NetworksResponse.class);
        if (body.networks() == null) {
            return List.of();
        }
        // 질의 필터를 무시하는 배포가 있어 응답 값을 다시 검사.
        return body.networks().stream()
                .filter(network -> Boolean.TRUE.equals(network.external()))
                .limit(OPTION_LIMIT)
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
                exchange(imagesUrl(session.imageEndpoint(resolvedRegion)), session.token(), HttpMethod.GET, null);
        OpenStackRecords.ImagesResponse body = parseBody(response.getBody(), OpenStackRecords.ImagesResponse.class);
        List<OpenStackRecords.Image> images = body.images() == null ? List.of() : body.images();
        List<VmOptionImage> results = new ArrayList<>();
        for (OpenStackRecords.Image image : images) {
            if (!matchesKeyword(image.name(), keyword)) {
                continue;
            }
            if (!CpuArchitecture.matches(architecture, image.architecture())) {
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
        return new OpenStackSession(token, interfaceName, preferredRegion, catalog, endpointOverrides());
    }

    /**
     * Glance 카탈로그는 버전 없는 base 를 반환하나 Pulumi 의 endpointOverrides 는 {@code /v2/}
     * 를 요구. 같은 자격증명 값이 양쪽을 만족해야 하므로 여기서 경로를 보정.
     */
    static String imagesUrl(String imageEndpoint) {
        String base =
                imageEndpoint.endsWith("/") ? imageEndpoint.substring(0, imageEndpoint.length() - 1) : imageEndpoint;
        return base.endsWith("/v2") ? base + "/images" : base + "/v2/images";
    }

    /** 손상된 override 로 preflight 전체를 실패시키지 않음. 카탈로그 값으로 대체. */
    Map<String, String> endpointOverrides() {
        String raw = resolveCredential("OS_ENDPOINT_OVERRIDES");
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
            Map<String, String> normalized = new java.util.HashMap<>();
            parsed.forEach((service, url) -> normalized.put(service.toLowerCase(Locale.ROOT), url));
            return normalized;
        } catch (Exception e) {
            log.warn("OS_ENDPOINT_OVERRIDES 파싱 실패 — 카탈로그 주소를 사용한다: {}", e.getMessage());
            return Map.of();
        }
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
                restTemplateFor(url), url, method, new HttpEntity<>(body, headers));
    }

    /**
     * OS_INSECURE 지정 시에만 인증서 검증을 끈 client 사용.
     *
     * <p>{@code cspRestTemplate} 은 모든 CSP 가 공유하는 singleton 이라 여기서 검증을 끄면 AWS,
     * Azure 호출의 검증도 함께 해제됨. 사설 CA 를 쓰는 OpenStack 이 있어 자격증명 단위로만 허용.
     * https 가 아니면 분기가 무의미하므로 공유 client 사용.
     */
    RestTemplate restTemplateFor(String url) {
        if (!insecureRequested() || url == null || !url.startsWith("https://")) {
            return restTemplate;
        }
        RestTemplate local = insecureRestTemplate;
        if (local == null) {
            synchronized (this) {
                local = insecureRestTemplate;
                if (local == null) {
                    insecureRestTemplate = local = buildInsecureRestTemplate();
                }
            }
        }
        return local;
    }

    private volatile RestTemplate insecureRestTemplate;

    private boolean insecureRequested() {
        String value = resolveCredential("OS_INSECURE");
        return value != null && INSECURE_TRUTHY.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static final Set<String> INSECURE_TRUTHY = Set.of("true", "1", "yes", "on");

    private static RestTemplate buildInsecureRestTemplate() {
        try {
            javax.net.ssl.SSLContext sslContext = org.apache.hc.core5.ssl.SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            var tlsStrategy = org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    // HttpClient 5.4+ 의 기본 정책 BUILTIN 은 JSSE 가 직접 검사해 아래 verifier
                    // 를 무시. CLIENT 지정 시에만 IP 접속용 사설 인증서가 통과.
                    .setHostVerificationPolicy(org.apache.hc.client5.http.ssl.HostnameVerificationPolicy.CLIENT)
                    .setHostnameVerifier(org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE)
                    .buildClassic();
            var connectionManager =
                    org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                            .setTlsSocketStrategy(tlsStrategy)
                            .build();
            var httpClient = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
            return new RestTemplate(
                    new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(httpClient));
        } catch (java.security.GeneralSecurityException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, "openstack", "OS_INSECURE", "TLS 검증 해제 client 생성 실패: " + e);
        }
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

    /**
     * flavor 이름으로 GPU 판별. 이름은 운영자가 정하므로 다른 단서 없음.
     *
     * <p>{@code novgpu} 처럼 부정형 이름이 존재. 부분 문자열만 보면 GPU 없는 flavor 를 GPU 로
     * 판정해 GPU 요청에 GPU 없는 노드가 배정됨.
     */
    private int detectGpuCount(String flavorName) {
        if (!StringUtils.hasText(flavorName)) {
            return 0;
        }
        String normalized = flavorName.toLowerCase(Locale.ROOT);
        if (normalized.contains("nogpu") || normalized.contains("novgpu") || normalized.contains("non-gpu")) {
            return 0;
        }
        return normalized.contains("gpu") ? 1 : 0;
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
            String token,
            String iface,
            String preferredRegion,
            List<OpenStackRecords.Service> serviceDirectory,
            Map<String, String> endpointOverrides) {

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

        String networkEndpoint(String region) {
            return serviceEndpoint("network", region);
        }

        private String serviceEndpoint(String serviceType, String region) {
            // 사설망 배포는 카탈로그가 외부 접근 불가 주소를 광고. Pulumi 의
            // openstack:endpointOverrides 와 동일한 자격증명 항목을 여기서도 참조.
            String override = endpointOverrides.get(serviceType.toLowerCase(Locale.ROOT));
            if (StringUtils.hasText(override)) {
                return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
            }
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
