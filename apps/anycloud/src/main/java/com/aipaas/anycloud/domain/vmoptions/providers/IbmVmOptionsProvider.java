package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.CpuArchitecture;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * IBM Cloud VPC 의 리전, 인스턴스 프로파일, 이미지 조회.
 *
 * <p>VPC API host 는 리전별로 분리({@code {region}.iaas.cloud.ibm.com}). 인증은 IAM 이 API 키를
 * 교환한 단기 토큰으로 수행하며, 토큰 발급 host 는 리전과 무관하게 단일.
 *
 * <p>프로비저닝이 요구하는 {@code providerSpec.zone} 은 리전이 아닌 존(예: {@code us-south-1}).
 * 존은 {@code VmOptionsProvider} 계약에 없어 provider config schema 에서 처리.
 */
@Component
@RequiredArgsConstructor
public class IbmVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IbmVmOptionsProvider.class);

    private static final String IAM_HOST = "iam.cloud.ibm.com";

    private static final String RESOURCE_CONTROLLER_HOST = "resource-controller.cloud.ibm.com";
    private static final String IAM_TOKEN_URL = "https://iam.cloud.ibm.com/identity/token";
    private static final String IAM_GRANT_TYPE = "urn:ibm:params:oauth:grant-type:apikey";

    private static final String API_KEY_ENV = "IBMCLOUD_API_KEY";

    /** VPC API 버전은 날짜로 고정. 상향 시 응답 필드 변경 가능하므로 코드와 함께 검토 필요. */
    private static final String API_VERSION = "2024-10-01";

    private static final String GENERATION = "2";

    /** 리전 목록은 모든 리전 host 에서 동일 응답. 목록 조회 전이라 하나를 고정 사용. */
    private static final String DEFAULT_REGION = "jp-tok";

    /** 이미지가 1,200건을 넘어 전부 받으면 화면이 느려진다. 한 페이지 크기이자 페이징 상한. */
    private static final int IMAGE_PAGE_SIZE = 100;

    private static final int MAX_IMAGE_PAGES = 10;

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.IBM;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(
                getProvider(),
                true,
                "IBM Cloud VPC API 로 리전, 인스턴스 프로파일, 이미지를 실시간 조회합니다. 프로비저닝에는 리전이 아니라 zone 이 필요합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        String token = accessToken();
        IbmRecords.RegionList body = get(DEFAULT_REGION, "/regions", token, "ibm-regions", IbmRecords.RegionList.class);
        if (body.regions() == null) {
            return List.of();
        }
        return body.regions().stream()
                .filter(region -> StringUtils.hasText(region.name()))
                .map(region -> VmOptionRegion.builder()
                        .provider(getProvider().getCanonicalName())
                        .id(region.name())
                        .name(region.name())
                        .available("available".equalsIgnoreCase(region.status()))
                        .build())
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        String resolved = requireRegion(region);
        String token = accessToken();
        IbmRecords.ProfileList body =
                get(resolved, "/instance/profiles", token, "ibm-instance-profiles", IbmRecords.ProfileList.class);
        if (body.profiles() == null) {
            return List.of();
        }

        List<VmOptionSpec> results = new ArrayList<>();
        for (IbmRecords.Profile profile : body.profiles()) {
            if (!matchesKeyword(profile.name(), keyword)) {
                continue;
            }
            int gpuCount = intValue(profile.gpuCount(), 0);
            if (gpuOnly && gpuCount <= 0) {
                continue;
            }
            results.add(toSpecDto(resolved, profile, gpuCount));
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
        String resolved = requireRegion(region);
        String token = accessToken();
        String host = vpcHost(resolved);
        String url = requireExpectedHost(
                "https://" + host + "/v1/images?" + versionQuery() + "&visibility=public&limit=" + IMAGE_PAGE_SIZE,
                host);

        List<VmOptionImage> results = new ArrayList<>();
        for (int page = 0; page < MAX_IMAGE_PAGES && StringUtils.hasText(url); page++) {
            IbmRecords.ImageList body = parseBody(exchange(url, token), "ibm-images", IbmRecords.ImageList.class);
            if (body.images() != null) {
                for (IbmRecords.Image image : body.images()) {
                    if (!"available".equalsIgnoreCase(image.status())) {
                        continue;
                    }
                    if (!matchesKeyword(image.name(), keyword)) {
                        continue;
                    }
                    String imageArchitecture = image.operatingSystem() == null
                            ? null
                            : image.operatingSystem().architecture();
                    if (!CpuArchitecture.matches(architecture, imageArchitecture)) {
                        continue;
                    }
                    String vendor = image.operatingSystem() == null
                            ? null
                            : image.operatingSystem().vendor();
                    if (StringUtils.hasText(owner) && !matchesKeyword(vendor, owner)) {
                        continue;
                    }
                    results.add(toImageDto(resolved, image));
                    if (results.size() >= limit) {
                        return sortedImages(results);
                    }
                }
            }
            // next.href 는 응답 본문 유래. 이 URL 재요청 시 IAM 토큰을 Bearer 로 첨부하므로,
            // host 미확인 시 응답 조작이 가능한 상대에게 토큰 전달.
            url = withVersion(
                    sameHostOrNull(body.next() == null ? null : body.next().href(), host));
        }
        return sortedImages(results);
    }

    /**
     * providerSpec.zone 은 리전이 아닌 존. 계정별 활성 zone 이 달라 {@code {region}-1} 로
     * 추정하면 생성 단계에서 실패하므로 목록 조회 후 선택.
     */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsFallback")
    public List<String> listConfigOptions(String configKey, String region) {
        return switch (configKey) {
            case "providerSpec.zone" -> StringUtils.hasText(region) ? listZones(region) : List.of();
                // 그룹 ID 는 콘솔 확인이 필요한 값. 계정에서 조회해 이름과 함께 제공.
            case "providerSpec.resourceGroup" -> listResourceGroups().stream()
                    .map(com.aipaas.anycloud.domain.vmoptions.api.ConfigOption::value)
                    .toList();
            default -> List.of();
        };
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsWithLabelsFallback")
    public List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabels(
            java.util.Map<String, String> credentials, String configKey, String region) {
        if (!"providerSpec.resourceGroup".equals(configKey)) {
            return super.listConfigOptionsWithLabels(credentials, configKey, region);
        }
        return withCredentials(credentials, this::listResourceGroups);
    }

    private List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabelsFallback(
            java.util.Map<String, String> credentials, String configKey, String region, Throwable throwable) {
        LOG.warn("IBM config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    /** 리소스 그룹은 리전 무관한 계정 단위 자원이라 VPC 대신 resource controller 에 조회. */
    private List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listResourceGroups() {
        String url = requireExpectedHost(
                "https://" + RESOURCE_CONTROLLER_HOST + "/v2/resource_groups", RESOURCE_CONTROLLER_HOST);
        IbmRecords.ResourceGroupList body =
                parseBody(exchange(url, accessToken()), "ibm-resource-groups", IbmRecords.ResourceGroupList.class);
        if (body.resources() == null) {
            return List.of();
        }
        return body.resources().stream()
                .filter(g -> StringUtils.hasText(g.id()) && !"REMOVED".equalsIgnoreCase(String.valueOf(g.state())))
                .map(g -> new com.aipaas.anycloud.domain.vmoptions.api.ConfigOption(
                        g.id(),
                        StringUtils.hasText(g.name())
                                ? (Boolean.TRUE.equals(g.isDefault()) ? g.name() + " (기본)" : g.name())
                                : g.id()))
                .toList();
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        LOG.warn("IBM config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    /** 계정에서 쓸 수 있는 zone 목록. */
    List<String> listZones(String region) {
        String resolved = requireRegion(region);
        String token = accessToken();
        IbmRecords.ZoneList body =
                get(resolved, "/regions/" + resolved + "/zones", token, "ibm-zones", IbmRecords.ZoneList.class);
        if (body.zones() == null) {
            return List.of();
        }
        return body.zones().stream()
                .filter(zone -> StringUtils.hasText(zone.name()))
                .filter(zone -> "available".equalsIgnoreCase(zone.status()))
                .map(IbmRecords.Zone::name)
                .sorted()
                .toList();
    }

    // ---- fallback ---------------------------------------------------------

    private List<VmOptionRegion> listRegionsFallback(Throwable throwable) {
        return emptyRegionsFallback("IBM", throwable);
    }

    private List<VmOptionSpec> listSpecsFallback(
            String region, String keyword, boolean gpuOnly, int limit, Throwable throwable) {
        return emptySpecsFallback("IBM", throwable);
    }

    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable throwable) {
        return emptyImagesFallback("IBM", throwable);
    }

    // ---- 매핑 -------------------------------------------------------------

    private VmOptionSpec toSpecDto(String region, IbmRecords.Profile profile, int gpuCount) {
        return VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(profile.name())
                .name(profile.name())
                .family(profile.family())
                .vcpu(intValue(profile.vcpuCount(), 0))
                // VPC API 의 memory 는 GiB 단위 정수다.
                .memoryGb((double) intValue(profile.memory(), 0))
                .gpuCount(gpuCount)
                .architecture(
                        profile.vcpuArchitecture() == null
                                ? null
                                : profile.vcpuArchitecture().value())
                .description(gpuModel(profile))
                .available(true)
                .build();
    }

    private VmOptionImage toImageDto(String region, IbmRecords.Image image) {
        IbmRecords.OperatingSystem os = image.operatingSystem();
        return VmOptionImage.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                // 프로비저닝은 이미지 이름으로 조회. OCID 같은 불투명 식별자 미사용.
                .id(image.name())
                .name(image.name())
                .osType(os == null ? null : os.family())
                .osVersion(os == null ? null : os.version())
                .architecture(os == null ? null : os.architecture())
                .owner(os == null ? null : os.vendor())
                .visibility(image.visibility())
                .createdAt(image.createdAt())
                .build();
    }

    private List<VmOptionImage> sortedImages(List<VmOptionImage> images) {
        return images.stream()
                .sorted(Comparator.comparing(VmOptionImage::getName))
                .toList();
    }

    private String gpuModel(IbmRecords.Profile profile) {
        if (profile.gpuModel() == null
                || profile.gpuModel().values() == null
                || profile.gpuModel().values().isEmpty()) {
            return null;
        }
        return "gpu=" + String.join(",", profile.gpuModel().values());
    }

    private int intValue(IbmRecords.IntValue holder, int fallback) {
        return holder == null || holder.value() == null ? fallback : holder.value();
    }

    // ---- HTTP -------------------------------------------------------------

    private <T> T get(String region, String path, String token, String source, Class<T> type) {
        String host = vpcHost(region);
        String url = requireExpectedHost("https://" + host + "/v1" + path + "?" + versionQuery(), host);
        return parseBody(exchange(url, token), source, type);
    }

    /**
     * IBM 이 반환하는 {@code next.href} 에는 version 과 generation 미포함. 그대로 재요청하면
     * 400 응답이라 두 번째 페이지부터 조회 중단.
     */
    private String withVersion(String url) {
        if (!StringUtils.hasText(url) || url.contains("version=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + versionQuery();
    }

    private String versionQuery() {
        return "version=" + API_VERSION + "&generation=" + GENERATION;
    }

    private String vpcHost(String region) {
        return region + ".iaas.cloud.ibm.com";
    }

    private String requireRegion(String region) {
        if (!StringUtils.hasText(region)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "IBM region is required");
        }
        return requireValidRegionId(region);
    }

    private String exchange(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            return restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(null, headers), String.class)
                    .getBody();
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "ibm",
                    url,
                    "IBM VM options request failed: " + e.getStatusCode().value());
        }
    }

    /**
     * API 키를 IAM 단기 토큰으로 교환.
     *
     * <p>호출마다 재발급. 토큰 캐시 시 자격증명 변경이나 회수 후에도 만료 시점까지 이전 권한으로
     * 조회 가능.
     */
    private String accessToken() {
        String apiKey = resolveCredential(API_KEY_ENV);
        if (!StringUtils.hasText(apiKey)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, "credential", API_KEY_ENV, API_KEY_ENV + " 가 필요합니다");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = "grant_type=" + encode(IAM_GRANT_TYPE) + "&apikey=" + encode(apiKey);
        try {
            String response = restTemplate
                    .exchange(
                            requireExpectedHost(IAM_TOKEN_URL, IAM_HOST),
                            HttpMethod.POST,
                            new HttpEntity<>(body, headers),
                            String.class)
                    .getBody();
            String token = parseBody(response, "ibm-token", IbmRecords.TokenResponse.class)
                    .accessToken();
            if (!StringUtils.hasText(token)) {
                throw new CustomException(ErrorCode.RUNTIME_EXCEPTION, "ibm", null, "IBM IAM 토큰을 발급받지 못했습니다");
            }
            return token;
        } catch (HttpClientErrorException e) {
            // 응답 본문에 키 일부가 포함되므로 상태 코드만 기록.
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "ibm",
                    null,
                    "IBM IAM 토큰 발급에 실패했습니다: " + e.getStatusCode().value());
        }
    }

    private <T> T parseBody(String body, String source, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, source, null, "IBM 응답을 해석하지 못했습니다: " + e.getMessage());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
