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
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
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
public class OciVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OciVmOptionsProvider.class);

    private static final String OCI_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss 'GMT'";

    @Qualifier("cspRestTemplate")
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.OCI;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(
                getProvider(), true, "OCI REST API 서명 기반으로 region subscription, shape, platform image를 실시간 조회합니다.");
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        List<OciRecords.RegionSubscription> items = listItems(
                exchange(regionSubscriptionsUrl(defaultRegion(), tenancyOcid())), OciRecords.RegionSubscription.class);
        List<VmOptionRegion> regions = new ArrayList<>();
        for (OciRecords.RegionSubscription sub : items) {
            if (!StringUtils.hasText(sub.regionName())) {
                continue;
            }
            regions.add(VmOptionRegion.builder()
                    .provider(getProvider().getCanonicalName())
                    .id(sub.regionName())
                    .name(sub.regionKey() != null ? sub.regionKey() : sub.regionName())
                    .available("READY".equalsIgnoreCase(sub.status()))
                    .build());
        }
        return regions.stream()
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        String resolvedRegion = resolveRegion(region);
        String availabilityDomain = firstAvailabilityDomain(resolvedRegion);
        String compartmentId = compartmentId();
        String url = computeBaseUrl(resolvedRegion) + "/20160918/shapes?compartmentId=" + compartmentId
                + "&availabilityDomain=" + availabilityDomain;
        List<OciRecords.Shape> items = listItems(exchange(url), OciRecords.Shape.class);
        List<VmOptionSpec> results = new ArrayList<>();
        for (OciRecords.Shape s : items) {
            String shape = s.shape();
            if (!matchesKeyword(shape, keyword) && !matchesKeyword(s.processorDescription(), keyword)) {
                continue;
            }
            Integer gpuCount = s.gpus() != null ? s.gpus() : inferGpuCount(shape);
            if (gpuOnly && Optional.ofNullable(gpuCount).orElse(0) <= 0) {
                continue;
            }
            boolean isFlex = s.shapeConfigOptions() != null
                    && !s.shapeConfigOptions().isMissingNode()
                    && !s.shapeConfigOptions().isNull();
            results.add(VmOptionSpec.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(shape)
                    .name(shape)
                    .family(inferFamily(shape))
                    .vcpu(s.ocpus())
                    .memoryGb(s.memoryInGBs())
                    .gpuCount(gpuCount)
                    .architecture(inferArchitecture(shape))
                    .description(isFlex ? "flex shape" : s.processorDescription())
                    .available(true)
                    .build());
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    /** ListImages 한 번에 받을 개수. 기본 페이지는 키워드 필터를 무의미하게 만든다. */
    private static final int IMAGE_PAGE_SIZE = 200;

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        String resolvedRegion = resolveRegion(region);
        /*
         * OCID 를 키워드로 받으면 목록을 뒤지지 않고 그 이미지를 바로 읽는다. 리전 카탈로그는
         * 수백 건이라 한 페이지에 안 들어오고, 이름 검색으로는 OCID 가 걸리지 않는다 — 멀쩡한
         * 이미지가 "없는 이미지" 로 판정돼 생성이 막혔다.
         */
        if (isImageOcid(keyword)) {
            VmOptionImage found = getImage(resolvedRegion, keyword);
            return found == null ? List.of() : List.of(found);
        }
        String compartmentId = compartmentId();
        StringBuilder url =
                new StringBuilder(computeBaseUrl(resolvedRegion) + "/20160918/images?compartmentId=" + compartmentId);
        String operatingSystem = StringUtils.hasText(owner) ? owner : operatingSystemFor(keyword);
        if (StringUtils.hasText(operatingSystem)) {
            url.append("&operatingSystem=")
                    .append(java.net.URLEncoder.encode(operatingSystem, java.nio.charset.StandardCharsets.UTF_8));
        }
        /*
         * 걸러내기는 여기서 한다. 기본 페이지만 받으면 Ubuntu 가 그 안에 없을 때 결과가 비어
         * "이 리전엔 Ubuntu 가 없다" 로 보인다.
         */
        url.append("&limit=").append(IMAGE_PAGE_SIZE);
        List<OciRecords.Image> items = listItems(exchange(url.toString()), OciRecords.Image.class);
        List<VmOptionImage> results = new ArrayList<>();
        for (OciRecords.Image img : items) {
            String displayName = img.displayName();
            if (!matchesKeyword(displayName, keyword)) {
                continue;
            }
            String imageArchitecture = inferArchitecture(displayName);
            if (StringUtils.hasText(architecture)
                    && !architecture.equalsIgnoreCase(
                            Optional.ofNullable(imageArchitecture).orElse(""))) {
                continue;
            }
            if (!"AVAILABLE".equalsIgnoreCase(img.lifecycleState())) {
                continue;
            }
            results.add(VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(resolvedRegion)
                    .id(img.id())
                    .name(displayName)
                    .osType(inferOsType(displayName))
                    .osVersion(inferOsVersion(displayName))
                    .architecture(imageArchitecture)
                    .owner(img.operatingSystem())
                    .visibility("platform")
                    .createdAt(img.timeCreated())
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

    /**
     * OCI 목록 응답을 typed record 리스트로 변환.
     *
     * <p>껍데기가 두 가지다. availabilityDomains, shapes, images 는 최상위가 배열이고, 페이지네이션을
     * 쓰는 엔드포인트만 {@code {"items":[...]}} 로 감싼다. 감싼 형태만 읽으면 배열 응답이 빈 목록이
     * 되어 "자원이 없다" 로 잘못 보고된다.
     *
     * <p>{@link ObjectMapper#convertValue} 로 element 단위 deserialize — schema mismatch 가
     * Jackson 의 명시적 에러로 잡힘 (JsonNode silent miss 와 대조적).
     */
    <T> List<T> listItems(JsonNode response, Class<T> type) {
        if (response == null) {
            return List.of();
        }
        JsonNode items = response.isArray() ? response : response.path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(items.size());
        for (JsonNode el : items) {
            out.add(objectMapper.convertValue(el, type));
        }
        return out;
    }

    /**
     * compartment OCID 는 콘솔을 열어 베껴 와야 하는 값이다. 계정에서 읽어 이름과 함께 보여 준다.
     *
     * <p>테넌시 자체도 compartment 다 — 루트에 바로 만드는 구성이 흔해 목록 맨 앞에 둔다.
     */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsFallback")
    public List<String> listConfigOptions(String configKey, String region) {
        return listCompartments(configKey).stream()
                .map(com.aipaas.anycloud.domain.vmoptions.api.ConfigOption::value)
                .toList();
    }

    /** OCID 만 내려보내면 화면에 식별자가 그대로 뜬다. 이름을 함께 준다. */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsWithLabelsFallback")
    public List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabels(
            java.util.Map<String, String> credentials, String configKey, String region) {
        return withCredentials(credentials, () -> listCompartments(configKey));
    }

    private List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listCompartments(String configKey) {
        if (!"providerSpec.compartmentId".equals(configKey)) {
            return List.of();
        }
        String tenancy = tenancyOcid();
        String url = identityBaseUrl(defaultRegion()) + "/20160918/compartments?compartmentId=" + tenancy
                + "&compartmentIdInSubtree=true&accessLevel=ACCESSIBLE&limit=" + OPTION_LIMIT;
        List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> out = new java.util.ArrayList<>();
        out.add(new com.aipaas.anycloud.domain.vmoptions.api.ConfigOption(tenancy, "테넌시 루트"));
        for (OciRecords.Compartment compartment : listItems(exchange(url), OciRecords.Compartment.class)) {
            // 삭제 중인 compartment 에 자원을 만들면 거절된다. 고를 수 있게 두면 안 된다.
            if (StringUtils.hasText(compartment.id()) && "ACTIVE".equalsIgnoreCase(compartment.lifecycleState())) {
                out.add(new com.aipaas.anycloud.domain.vmoptions.api.ConfigOption(
                        compartment.id(),
                        StringUtils.hasText(compartment.name()) ? compartment.name() : compartment.id()));
            }
        }
        return out;
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        // 조회가 막혀도 자유 입력으로 남는다. 목록을 못 준다고 생성을 막을 이유는 없다.
        LOG.warn("OCI config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    private List<com.aipaas.anycloud.domain.vmoptions.api.ConfigOption> listConfigOptionsWithLabelsFallback(
            java.util.Map<String, String> credentials, String configKey, String region, Throwable throwable) {
        LOG.warn("OCI config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    /** 선택 상자에 담을 최대 개수. compartment 가 수백 개인 테넌시가 있다. */
    private static final int OPTION_LIMIT = 200;

    private String firstAvailabilityDomain(String region) {
        String url = identityBaseUrl(region) + "/20160918/availabilityDomains?compartmentId=" + tenancyOcid();
        List<OciRecords.AvailabilityDomain> items = listItems(exchange(url), OciRecords.AvailabilityDomain.class);
        for (OciRecords.AvailabilityDomain ad : items) {
            String name = ad.name();
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                "availabilityDomain",
                region,
                "No OCI availability domain found for region");
    }

    private JsonNode exchange(String url) {
        try {
            HttpHeaders headers = buildHeaders(url);
            /*
             * URI 로 넘긴다. String 오버로드는 URI 템플릿으로 취급해 이미 인코딩된 값을 한 번 더
             * 인코딩한다 — operatingSystem 의 %20 이 %2520 이 되어 아무것도 걸리지 않는다.
             */
            ResponseEntity<String> response = restTemplate.exchange(
                    java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseBody(response.getBody(), url);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci",
                    url,
                    "OCI VM options request failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    /**
     * 그 shape 를 지금 이 AD 에 띄울 수 있는지 묻는다.
     *
     * <p>용량은 AD 단위로 수시로 바뀐다. 확인하지 않으면 VCN, 서브넷, 라우터를 다 만든 뒤
     * 인스턴스 단계에서 {@code Out of host capacity} 로 끝나고 롤백한다.
     *
     * @return 가용한 AD 이름. 어디에도 자리가 없으면 비어 있다
     */
    public java.util.Optional<String> findAvailabilityDomainWithCapacity(
            Map<String, String> credentials, String region, String shape, int ocpus, int memoryGb) {
        return withCredentials(credentials, () -> {
            String resolved = resolveRegion(region);
            String compartment = compartmentId();
            for (OciRecords.AvailabilityDomain ad : availabilityDomains(resolved, compartment)) {
                if (hasCapacity(resolved, compartment, ad.name(), shape, ocpus, memoryGb)) {
                    return java.util.Optional.of(ad.name());
                }
            }
            return java.util.Optional.<String>empty();
        });
    }

    private List<OciRecords.AvailabilityDomain> availabilityDomains(String region, String compartment) {
        String url = identityBaseUrl(region) + "/20160918/availabilityDomains?compartmentId=" + compartment;
        return listItems(exchange(url), OciRecords.AvailabilityDomain.class);
    }

    private boolean hasCapacity(String region, String compartment, String ad, String shape, int ocpus, int memoryGb) {
        Map<String, Object> availability = new java.util.LinkedHashMap<>();
        availability.put("instanceShape", shape);
        // 고정 shape 에 shapeConfig 를 주면 거절된다. Flex 계열만 코어와 메모리를 받는다.
        if (shape.endsWith(".Flex")) {
            availability.put("instanceShapeConfig", Map.of("ocpus", ocpus, "memoryInGBs", memoryGb));
        }
        Map<String, Object> body = Map.of(
                "compartmentId", compartment,
                "availabilityDomain", ad,
                "shapeAvailabilities", List.of(availability));
        JsonNode report = exchangePost(computeBaseUrl(region) + "/20160918/computeCapacityReports", body);
        for (JsonNode entry : report.path("shapeAvailabilities")) {
            if ("AVAILABLE".equalsIgnoreCase(entry.path("availabilityStatus").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode exchangePost(String url, Map<String, Object> body) {
        String payload = writeJson(body);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    java.net.URI.create(url),
                    HttpMethod.POST,
                    new HttpEntity<>(payload, buildPostHeaders(url, payload)),
                    String.class);
            return parseBody(response.getBody(), url);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci",
                    url,
                    "OCI VM options request failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.RUNTIME_EXCEPTION, "oci", null, "본문을 만들지 못했습니다");
        }
    }

    /** POST 는 본문 해시까지 서명해야 한다. GET 서명에 본문 헤더만 더하면 401 로 거절된다. */
    private HttpHeaders buildPostHeaders(String url, String payload) {
        URI uri = URI.create(url);
        String date = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern(OCI_DATE_FORMAT, Locale.US));
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        String digest = Base64.getEncoder().encodeToString(sha256(raw));
        String signingString = "(request-target): post " + uri.getRawPath() + "\n"
                + "date: " + date + "\n"
                + "host: " + uri.getHost() + "\n"
                + "content-length: " + raw.length + "\n"
                + "content-type: application/json\n"
                + "x-content-sha256: " + digest;
        String authorization = "Signature version=\"1\",keyId=\"" + tenancyOcid() + "/" + userOcid() + "/"
                + fingerprint() + "\",algorithm=\"rsa-sha256\",headers=\"(request-target) date host "
                + "content-length content-type x-content-sha256\",signature=\"" + sign(signingString) + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.add("date", date);
        headers.add("host", uri.getHost());
        headers.add("x-content-sha256", digest);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(raw.length);
        headers.add("authorization", authorization);
        return headers;
    }

    private byte[] sha256(byte[] payload) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.RUNTIME_EXCEPTION, "oci", null, "본문 해시를 만들지 못했습니다");
        }
    }

    private HttpHeaders buildHeaders(String url) {
        URI uri = URI.create(url);
        String date = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern(OCI_DATE_FORMAT, Locale.US));
        String requestTarget = "(request-target): get " + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String hostHeader = "host: " + uri.getHost();
        String dateHeader = "date: " + date;
        String signingString = requestTarget + "\n" + dateHeader + "\n" + hostHeader;
        String signature = sign(signingString);

        String authorization = "Signature version=\"1\",keyId=\"" + tenancyOcid() + "/" + userOcid() + "/"
                + fingerprint() + "\",algorithm=\"rsa-sha256\",headers=\"(request-target) date host\",signature=\""
                + signature + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.add("date", date);
        headers.add("host", uri.getHost());
        headers.add("authorization", authorization);
        return headers;
    }

    private String sign(String payload) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci-signature",
                    null,
                    "Failed to sign OCI VM options request: " + e.getMessage());
        }
    }

    private PrivateKey privateKey() {
        try {
            String pem = resolveCredential("TF_VAR_private_key");
            if (!StringUtils.hasText(pem)) {
                String path = resolveCredential("TF_VAR_private_key_path");
                if (StringUtils.hasText(path)) {
                    pem = Files.readString(Path.of(path));
                }
            }
            if (!StringUtils.hasText(pem)) {
                throw new CustomException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "TF_VAR_private_key",
                        null,
                        "TF_VAR_private_key or TF_VAR_private_key_path is required for OCI VM options");
            }
            try (PEMParser pemParser = new PEMParser(new java.io.StringReader(pem))) {
                Object object = pemParser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                if (object instanceof PEMKeyPair keyPair) {
                    return converter.getKeyPair(keyPair).getPrivate();
                }
                if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo privateKeyInfo) {
                    return converter.getPrivateKey(privateKeyInfo);
                }
            }
            String normalized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci-private-key",
                    null,
                    "Failed to read OCI private key: " + e.getMessage());
        }
    }

    private JsonNode parseBody(String body, String source) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci-response",
                    source,
                    "Failed to parse OCI VM options response: " + e.getMessage());
        }
    }

    // region 이 host 에 들어간다. 검증 없이 넣으면 host 가 통째로 바뀌고,
    // buildHeaders() 가 만든 요청 서명이 그대로 따라 나간다.
    private String identityBaseUrl(String region) {
        return ociBaseUrl("identity", region);
    }

    private String computeBaseUrl(String region) {
        return ociBaseUrl("iaas", region);
    }

    private String ociBaseUrl(String service, String region) {
        String host = service + "." + requireValidRegionId(region) + ".oraclecloud.com";
        return requireExpectedHost("https://" + host, host);
    }

    private String resolveRegion(String region) {
        return StringUtils.hasText(region) ? region : defaultRegion();
    }

    private String defaultRegion() {
        String region = resolveCredential("TF_VAR_region");
        if (!StringUtils.hasText(region)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "TF_VAR_region",
                    null,
                    "TF_VAR_region is required for OCI VM options");
        }
        return region;
    }

    /**
     * 구독 리전 조회 URL.
     *
     * <p>테넌시 하위 경로다. {@code /regionSubscriptions/{tenancyId}} 로 부르면 404
     * NotAuthorizedOrNotFound 가 오는데, 메시지가 권한 문제처럼 읽혀 자격증명을 의심하게 만든다.
     */
    private String regionSubscriptionsUrl(String region, String tenancyId) {
        return identityBaseUrl(region) + "/20160918/tenancies/" + tenancyId + "/regionSubscriptions";
    }

    private String tenancyOcid() {
        return requiredEnv("TF_VAR_tenancy_ocid");
    }

    private String userOcid() {
        return requiredEnv("TF_VAR_user_ocid");
    }

    private String fingerprint() {
        return requiredEnv("TF_VAR_fingerprint");
    }

    private String compartmentId() {
        String env = resolveCredential("OCI_COMPARTMENT_ID");
        if (StringUtils.hasText(env)) {
            return env;
        }
        String tf = resolveCredential("TF_VAR_compartment_ocid");
        if (StringUtils.hasText(tf)) {
            return tf;
        }
        return tenancyOcid();
    }

    private String requiredEnv(String key) {
        String value = resolveCredential(key);
        if (!StringUtils.hasText(value)) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, key, null, key + " is required for OCI VM options");
        }
        return value;
    }

    private String inferFamily(String shape) {
        if (!StringUtils.hasText(shape)) {
            return null;
        }
        int index = shape.lastIndexOf('.');
        return index > 0 ? shape.substring(0, index) : shape;
    }

    private Integer inferGpuCount(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return value.toLowerCase(Locale.ROOT).contains("gpu") ? 1 : 0;
    }

    private String inferArchitecture(String value) {
        if (!StringUtils.hasText(value)) {
            return "x86_64";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("a1") || normalized.contains("arm")) {
            return "arm64";
        }
        return "x86_64";
    }

    private String inferOsType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).contains("windows") ? "windows" : "linux";
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
        if (normalized.contains("oracle linux 9")) {
            return "9";
        }
        return null;
    }

    private String optionalText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
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
    /**
     * 목록이 Windows 로 먼저 채워져 Ubuntu 가 첫 페이지에 오지 않는다. 이름으로만 거르면 결과가
     * 비어 "이 리전엔 Ubuntu 가 없다" 로 보인다 — OCI 는 배포판을 operatingSystem 으로 준다.
     */
    private String operatingSystemFor(String keyword) {
        return StringUtils.hasText(keyword)
                        && keyword.toLowerCase(java.util.Locale.ROOT).contains("ubuntu")
                ? "Canonical Ubuntu"
                : null;
    }

    private boolean isImageOcid(String value) {
        return value != null && value.startsWith("ocid1.image.");
    }

    private VmOptionImage getImage(String region, String imageId) {
        try {
            JsonNode body = exchange(computeBaseUrl(region) + "/20160918/images/"
                    + java.net.URLEncoder.encode(imageId, java.nio.charset.StandardCharsets.UTF_8));
            OciRecords.Image img = body == null ? null : objectMapper.convertValue(body, OciRecords.Image.class);
            if (img == null || !"AVAILABLE".equalsIgnoreCase(img.lifecycleState())) {
                return null;
            }
            return VmOptionImage.builder()
                    .provider(getProvider().getCanonicalName())
                    .region(region)
                    .id(img.id())
                    .name(img.displayName())
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("OCI 이미지 단건 조회 실패 id={}: {}", imageId, String.valueOf(e));
            return null;
        }
    }

    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }
}
