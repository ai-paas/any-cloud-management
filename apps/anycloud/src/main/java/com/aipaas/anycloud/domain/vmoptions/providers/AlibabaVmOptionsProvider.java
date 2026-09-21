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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class AlibabaVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final String API_VERSION = "2014-05-26";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.ALIBABA;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "Alibaba ECS OpenAPI 기반으로 리전, 인스턴스 타입, 공개 OS 이미지를 실시간 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        AlibabaRecords.RegionsResponse body =
                invoke("DescribeRegions", null, Map.of(), AlibabaRecords.RegionsResponse.class);
        List<AlibabaRecords.Region> regions =
                body.Regions() == null || body.Regions().Region() == null
                        ? List.of()
                        : body.Regions().Region();
        List<VmOptionRegion> results = new ArrayList<>();
        for (AlibabaRecords.Region region : regions) {
            String regionId = region.RegionId();
            if (!StringUtils.hasText(regionId)) {
                continue;
            }
            results.add(VmOptionRegion.builder()
                    .provider(getProvider().getCanonicalName())
                    .id(regionId)
                    .name(region.LocalName() != null ? region.LocalName() : regionId)
                    .available(true)
                    .build());
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AlibabaVmOptionsProvider.class);

    /**
     * VSwitch 가 zone 단위라 zone 을 골라야 서브넷이 만들어진다.
     *
     * <p>zone 이름은 콘솔을 열어야 알 수 있고, 같은 리전이라도 zone 마다 쓸 수 있는 인스턴스
     * 타입이 다르다. 없는 조합을 고르면 재고 없음으로 생성이 막힌다.
     */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsFallback")
    public List<String> listConfigOptions(String configKey, String region) {
        if (!"providerSpec.zone".equals(configKey) || !StringUtils.hasText(region)) {
            return List.of();
        }
        AlibabaRecords.ZonesResponse body =
                invoke("DescribeZones", requiredRegion(region), Map.of(), AlibabaRecords.ZonesResponse.class);
        List<AlibabaRecords.Zone> zones = body.Zones() == null || body.Zones().Zone() == null
                ? List.of()
                : body.Zones().Zone();
        return zones.stream()
                .map(AlibabaRecords.Zone::ZoneId)
                .filter(StringUtils::hasText)
                .sorted()
                .toList();
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        // 조회가 막혀도 자유 입력으로 남는다. 목록을 못 준다고 생성을 막을 이유는 없다.
        LOG.warn("Alibaba config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        String resolvedRegion = requiredRegion(region);
        AlibabaRecords.InstanceTypesResponse body =
                invoke("DescribeInstanceTypes", resolvedRegion, Map.of(), AlibabaRecords.InstanceTypesResponse.class);
        List<AlibabaRecords.InstanceType> instanceTypes =
                body.InstanceTypes() == null || body.InstanceTypes().InstanceType() == null
                        ? List.of()
                        : body.InstanceTypes().InstanceType();
        List<VmOptionSpec> results = new ArrayList<>();
        for (AlibabaRecords.InstanceType it : instanceTypes) {
            if (!matchesKeyword(it.InstanceTypeId(), keyword) && !matchesKeyword(it.InstanceTypeFamily(), keyword)) {
                continue;
            }
            Integer gpuCount = parseInteger(it.GPUAmount());
            if (gpuCount == null) {
                gpuCount = inferGpuCount(it.InstanceTypeId());
            }
            if (gpuOnly && Optional.ofNullable(gpuCount).orElse(0) <= 0) {
                continue;
            }
            results.add(VmOptionSpec.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(it.InstanceTypeId())
                    .name(it.InstanceTypeId())
                    .family(it.InstanceTypeFamily())
                    .vcpu(it.CpuCoreCount())
                    .memoryGb(it.MemorySize())
                    .gpuCount(gpuCount)
                    .architecture(it.CpuArchitecture())
                    .description(it.InstanceTypeFamilyLevel())
                    .available(true)
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    /** DescribeImages 의 상한. 기본 10 은 키워드 필터를 무의미하게 만든다. */
    private static final int IMAGE_PAGE_SIZE = 100;

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        String resolvedRegion = requiredRegion(region);
        AlibabaRecords.ImagesResponse body = invoke(
                "DescribeImages",
                resolvedRegion,
                /*
                 * PageSize 를 주지 않으면 10건만 온다. 걸러내기는 여기서 하므로 첫 페이지에
                 * Ubuntu 가 없으면 결과가 통째로 비어 "이 리전엔 Ubuntu 가 없다" 로 보인다.
                 */
                Map.of("ImageOwnerAlias", ownerOrDefault(owner), "PageSize", String.valueOf(IMAGE_PAGE_SIZE)),
                AlibabaRecords.ImagesResponse.class);
        List<AlibabaRecords.Image> images =
                body.Images() == null || body.Images().Image() == null
                        ? List.of()
                        : body.Images().Image();
        List<VmOptionImage> results = new ArrayList<>();
        for (AlibabaRecords.Image img : images) {
            if (!matchesKeyword(img.ImageName(), keyword) && !matchesKeyword(img.ImageId(), keyword)) {
                continue;
            }
            if (StringUtils.hasText(architecture)
                    && !architecture.equalsIgnoreCase(
                            Optional.ofNullable(img.Architecture()).orElse(""))) {
                continue;
            }
            results.add(VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(img.ImageId())
                    .name(img.ImageName())
                    .osType(inferOsType(img.ImageName()))
                    .osVersion(inferOsVersion(img.ImageName()))
                    .architecture(img.Architecture())
                    .owner(img.ImageOwnerAlias())
                    .visibility(Boolean.TRUE.equals(img.IsPublic()) ? "public" : "private")
                    .createdAt(img.CreationTime())
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results.stream()
                .sorted(Comparator.comparing(VmOptionImage::getCreatedAt, Comparator.nullsLast(String::compareTo))
                        .reversed())
                .toList();
    }

    private <T> T invoke(String action, String regionId, Map<String, String> extraParameters, Class<T> type) {
        Map<String, String> params = new TreeMap<>();
        params.put("Action", action);
        params.put("Format", "JSON");
        params.put("Version", API_VERSION);
        params.put("AccessKeyId", accessKeyId());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("Timestamp", TIMESTAMP_FORMATTER.format(Instant.now()));
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        if (StringUtils.hasText(regionId)) {
            params.put("RegionId", regionId);
        }
        extraParameters.forEach((key, value) -> {
            if (StringUtils.hasText(value)) {
                params.put(key, value);
            }
        });

        String canonical = canonicalQuery(params);
        params.put("Signature", sign(canonical));
        String endpoint = endpoint(regionId);
        String url = endpoint + "?" + canonicalQuery(params);
        try {
            /*
             * URI 로 넘긴다. String 오버로드는 URI 템플릿으로 취급해 이미 인코딩된 값을 한 번 더
             * 인코딩한다 — Timestamp 의 %3A 가 %253A 가 되어 "time stamp is not well formatted"
             * 로 거절된다. 서명은 인코딩된 문자열로 계산했으므로 그대로 보내야 한다.
             */
            ResponseEntity<String> response = restTemplate.exchange(
                    java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
            return parseBody(response.getBody(), action, type);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "alibaba",
                    action,
                    "Alibaba VM options request failed: " + e.getStatusCode().value() + " "
                            + e.getResponseBodyAsString());
        }
    }

    private String endpoint(String regionId) {
        if (!StringUtils.hasText(regionId)) {
            return "https://ecs.aliyuncs.com/";
        }
        // region 이 host 에 들어간다. 검증 없이 넣으면 host 가 통째로 바뀌고,
        // 쿼리스트링의 AccessKeyId 와 Signature 가 그대로 따라 나간다.
        String region = requireValidRegionId(regionId);
        String host = "ecs." + region + ".aliyuncs.com";
        return requireExpectedHost("https://" + host + "/", host);
    }

    private String sign(String canonicalQuery) {
        try {
            String stringToSign = "GET&%2F&" + percentEncode(canonicalQuery);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secretKey() + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "alibaba-signature",
                    null,
                    "Failed to sign Alibaba VM options request: " + e.getMessage());
        }
    }

    private String canonicalQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String percentEncode(String value) {
        return URLEncoder.encode(Optional.ofNullable(value).orElse(""), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private <T> T parseBody(String body, String action, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "alibaba-response",
                    action,
                    "Failed to parse Alibaba VM options response: " + e.getMessage());
        }
    }

    private String requiredRegion(String region) {
        if (StringUtils.hasText(region)) {
            return region;
        }
        String envRegion = resolveCredential("ALICLOUD_REGION");
        if (StringUtils.hasText(envRegion)) {
            return envRegion;
        }
        throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "Alibaba region is required");
    }

    private String accessKeyId() {
        String value = resolveCredential("ALICLOUD_ACCESS_KEY");
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "ALICLOUD_ACCESS_KEY",
                    null,
                    "ALICLOUD_ACCESS_KEY is required for Alibaba VM options");
        }
        return value;
    }

    private String secretKey() {
        String value = resolveCredential("ALICLOUD_SECRET_KEY");
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "ALICLOUD_SECRET_KEY",
                    null,
                    "ALICLOUD_SECRET_KEY is required for Alibaba VM options");
        }
        return value;
    }

    /**
     * 존마다 파는 인스턴스가 다르다.
     *
     * <p>DescribeInstanceTypes 는 리전 전체 목록이라 {@code ecs.ga1.xlarge} 가 있다고 나오지만
     * ap-northeast-2a 에는 없다. 그대로 만들면 403 InvalidResourceType.NotSupported 로 끝난다.
     */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "isInstanceTypeAvailableInZoneFallback")
    public boolean isInstanceTypeAvailableInZone(
            Map<String, String> credentials, String region, String zone, String instanceType) {
        if (!StringUtils.hasText(instanceType) || !StringUtils.hasText(zone)) {
            return true;
        }
        return withCredentials(credentials, () -> {
            AlibabaRecords.AvailableResourceResponse body = invoke(
                    "DescribeAvailableResource",
                    requiredRegion(region),
                    Map.of(
                            "DestinationResource",
                            "InstanceType",
                            "ZoneId",
                            zone,
                            "InstanceType",
                            instanceType,
                            "InstanceChargeType",
                            "PostPaid"),
                    AlibabaRecords.AvailableResourceResponse.class);
            return supportsInstanceType(body, zone, instanceType);
        });
    }

    private boolean supportsInstanceType(
            AlibabaRecords.AvailableResourceResponse body, String zone, String instanceType) {
        if (body.AvailableZones() == null || body.AvailableZones().AvailableZone() == null) {
            return false;
        }
        for (AlibabaRecords.AvailableResourceResponse.AvailableZone available :
                body.AvailableZones().AvailableZone()) {
            if (!zone.equalsIgnoreCase(available.ZoneId()) || available.AvailableResources() == null) {
                continue;
            }
            for (AlibabaRecords.AvailableResourceResponse.AvailableResource resource :
                    available.AvailableResources().AvailableResource()) {
                if (resource.SupportedResources() == null) {
                    continue;
                }
                for (AlibabaRecords.AvailableResourceResponse.SupportedResource supported :
                        resource.SupportedResources().SupportedResource()) {
                    // Available 이 아닌 값은 재고가 없거나 판매하지 않는 것이다.
                    if (instanceType.equalsIgnoreCase(supported.Value())
                            && "Available".equalsIgnoreCase(supported.Status())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInstanceTypeAvailableInZoneFallback(
            Map<String, String> credentials, String region, String zone, String instanceType, Throwable throwable) {
        // 조회가 막히면 막지 않는다. 판단할 수 없다고 정상 요청을 거절할 이유는 없다.
        LOG.warn("Alibaba zone 가용성 확인 실패 type={} zone={}: {}", instanceType, zone, String.valueOf(throwable));
        return true;
    }

    private String ownerOrDefault(String owner) {
        return StringUtils.hasText(owner) ? owner : "system";
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

    private Integer inferGpuCount(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return value.toLowerCase(Locale.ROOT).contains("gpu") ? 1 : 0;
    }

    private String inferOsType(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) {
            return "windows";
        }
        return "linux";
    }

    private String inferOsVersion(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("24.04")) {
            return "24.04";
        }
        if (normalized.contains("22.04")) {
            return "22.04";
        }
        if (normalized.contains("20.04")) {
            return "20.04";
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
