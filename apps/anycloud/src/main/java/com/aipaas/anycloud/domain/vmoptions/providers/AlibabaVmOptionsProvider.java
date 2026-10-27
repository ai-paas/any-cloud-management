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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * VSwitch 가 zone 단위이므로 zone 선택이 서브넷 생성 조건.
     *
     * <p>zone 이름은 콘솔 확인이 필요하고, 동일 리전에서도 zone 별 사용 가능 인스턴스 타입이 상이.
     * 미제공 조합 선택 시 재고 없음으로 생성 차단.
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
        // 조회 실패 시 자유 입력으로 유지. 목록 제공 실패가 생성 차단 사유는 아님.
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
        Set<String> sellable = sellableTypes(resolvedRegion);
        List<VmOptionSpec> results = new ArrayList<>();
        for (AlibabaRecords.InstanceType it : instanceTypes) {
            if (!matchesKeyword(it.InstanceTypeId(), keyword) && !matchesKeyword(it.InstanceTypeFamily(), keyword)) {
                continue;
            }
            if (sellable != null && !sellable.contains(it.InstanceTypeId())) {
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

    /**
     * 리전에서 판매 중인 타입 집합. 판단 불가 시 {@code null} 반환, 이 경우 필터링하지 않음.
     *
     * <p>DescribeInstanceTypes 는 리전 구분 없이 전체 카탈로그를 반환. 서울에 없는 구형 ga1,
     * gn4 가 vCPU 순으로 앞에 배치되어 목록 앞부분만 보면 리전에 있는 gn7i 에 도달하지 못함.
     */
    private Set<String> sellableTypes(String region) {
        try {
            AlibabaRecords.AvailableResourceResponse body = invoke(
                    "DescribeAvailableResource",
                    region,
                    Map.of("DestinationResource", "InstanceType", "InstanceChargeType", "PostPaid"),
                    AlibabaRecords.AvailableResourceResponse.class);
            Set<String> types = collectAvailableTypes(body);
            return types.isEmpty() ? null : types;
        } catch (RuntimeException e) {
            LOG.warn("Alibaba 리전 판매 타입 조회 실패 region={}: {}", region, String.valueOf(e));
            return null;
        }
    }

    private Set<String> collectAvailableTypes(AlibabaRecords.AvailableResourceResponse body) {
        Set<String> types = new LinkedHashSet<>();
        if (body.AvailableZones() == null || body.AvailableZones().AvailableZone() == null) {
            return types;
        }
        for (AlibabaRecords.AvailableResourceResponse.AvailableZone zone :
                body.AvailableZones().AvailableZone()) {
            if (zone.AvailableResources() == null) {
                continue;
            }
            for (AlibabaRecords.AvailableResourceResponse.AvailableResource resource :
                    zone.AvailableResources().AvailableResource()) {
                if (resource.SupportedResources() == null) {
                    continue;
                }
                for (AlibabaRecords.AvailableResourceResponse.SupportedResource supported :
                        resource.SupportedResources().SupportedResource()) {
                    if (AVAILABLE_STATUS.equalsIgnoreCase(supported.Status())
                            && StringUtils.hasText(supported.Value())) {
                        types.add(supported.Value());
                    }
                }
            }
        }
        return types;
    }

    /** DescribeAvailableResource 가 "팔고 있다" 고 답하는 값. */
    private static final String AVAILABLE_STATUS = "Available";

    /** DescribeImages 상한. 기본값 10 에서는 키워드 필터가 동작하지 않음. */
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
            if (!CpuArchitecture.matches(architecture, img.Architecture())) {
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
             * URI 타입으로 전달. String 오버로드는 URI 템플릿으로 취급해 인코딩된 값을 재인코딩.
             * Timestamp 의 %3A 가 %253A 가 되어 "time stamp is not well formatted" 로 거절됨.
             * 서명은 인코딩된 문자열 기준으로 계산했으므로 원본 그대로 전송 필요.
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
        // region 이 host 에 포함. 미검증 입력 시 host 전체가 치환되고 쿼리스트링의
        // AccessKeyId 와 Signature 도 그대로 전송.
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
     * 존별 제공 인스턴스 상이.
     *
     * <p>DescribeInstanceTypes 는 리전 전체 목록이라 {@code ecs.ga1.xlarge} 가 포함되나
     * ap-northeast-2a 에는 미제공. 그대로 생성 시 403 InvalidResourceType.NotSupported.
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
                    // Available 이 아닌 상태는 재고 없음 또는 미판매.
                    if (instanceType.equalsIgnoreCase(supported.Value())
                            && AVAILABLE_STATUS.equalsIgnoreCase(supported.Status())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInstanceTypeAvailableInZoneFallback(
            Map<String, String> credentials, String region, String zone, String instanceType, Throwable throwable) {
        // 조회 실패 시 통과 처리. 판단 불가를 거절 사유로 두지 않음.
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
