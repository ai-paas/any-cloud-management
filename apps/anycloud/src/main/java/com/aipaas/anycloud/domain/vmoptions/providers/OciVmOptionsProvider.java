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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
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
public class OciVmOptionsProvider extends AbstractVmOptionsProvider {

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
                exchange(identityBaseUrl(defaultRegion()) + "/20160918/regionSubscriptions/" + tenancyOcid()),
                OciRecords.RegionSubscription.class);
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

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        String resolvedRegion = resolveRegion(region);
        String compartmentId = compartmentId();
        StringBuilder url =
                new StringBuilder(computeBaseUrl(resolvedRegion) + "/20160918/images?compartmentId=" + compartmentId);
        if (StringUtils.hasText(owner)) {
            url.append("&operatingSystem=").append(owner);
        }
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
     * L1: OCI response 의 {@code {"items":[...]}} array 를 typed record 리스트로 변환.
     * {@link ObjectMapper#convertValue} 로 element 단위 deserialize — schema mismatch 가
     * Jackson 의 명시적 에러로 잡힘 (JsonNode silent miss 와 대조적).
     */
    private <T> List<T> listItems(JsonNode response, Class<T> type) {
        JsonNode items = response == null ? null : response.path("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(items.size());
        for (JsonNode el : items) {
            out.add(objectMapper.convertValue(el, type));
        }
        return out;
    }

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
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseBody(response.getBody(), url);
        } catch (HttpClientErrorException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION,
                    "oci",
                    url,
                    "OCI VM options request failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
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
    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }
}
