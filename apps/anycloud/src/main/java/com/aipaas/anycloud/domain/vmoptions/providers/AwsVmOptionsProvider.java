package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;
import java.util.HashSet;
import java.util.Set;

@Component
public class AwsVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final List<String> DEFAULT_OWNERS = List.of("099720109477", "amazon");

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.AWS;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "EC2 API 기반으로 리전, VM 스펙, 공개 OS 이미지를 실시간 조회합니다.");
    }

    @Override
    public List<VmOptionRegion> listRegions() {
        return Region.regions().stream()
                .map(region -> VmOptionRegion.builder()
                        .provider(getProvider().getCanonicalName())
                        .id(region.id())
                        .name(region.id())
                        .available(true)
                        .build())
                .sorted(Comparator.comparing(VmOptionRegion::getId))
                .toList();
    }

    /**
     * EC2 API 장애 시 csp-api circuit breaker 가 30초 OPEN → 그 사이 호출 즉시 실패. fallback 은
     * 빈 array — UI 가 5xx 로 무너지지 않게 부분 가용성 유지. failure-rate 50% 이상에서 OPEN.
     *
     * <p>credentials 없이 호출되면 SDK default provider chain (env / ~/.aws/credentials / EC2 instance
     * role) — 백엔드 머신의 default credential. 운영에서는 listSpecs(creds, ...) overload 를 사용해
     * 사용자가 등록한 credential 을 명시 주입해야 IAM 사용 audit 가 의미 있음.
     */
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    @Override
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        return doListSpecs(buildClient(region, null), region, keyword, gpuOnly, limit);
    }

    /**
     * Credential-aware overload — VmOptionsQueryServiceImpl 가 본 method 호출 (default 구현은 credentials 를
     * 버리는 fallback 이라 backend 머신의 default credential 로 잘못 동작했었다 — fix).
     *
     * @param credentials decrypted credential map: {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}
     */
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listSpecsFallback")
    @Override
    public List<VmOptionSpec> listSpecs(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit) {
        return doListSpecs(buildClient(region, credentials), region, keyword, gpuOnly, limit);
    }

    private List<VmOptionSpec> doListSpecs(
            Ec2Client clientArg, String region, String keyword, boolean gpuOnly, int limit) {
        try (Ec2Client client = clientArg) {
            // DescribeInstanceTypes 만으로는 region availability 부정확 — AZ-level offering 까지 봐야
            // launch 가능한 type 셋이 확정. DescribeInstanceTypeOfferings 가 source-of-truth.
            // 두 API 모두 region endpoint 의존이지만 Offerings 만 "이 region 의 어느 AZ 에서든 launch 가능" 보장.
            Set<String> availableInRegion = fetchAvailableInstanceTypeNames(client, region);
            if (availableInRegion.isEmpty()) {
                return List.of();
            }

            List<VmOptionSpec> results = new ArrayList<>();
            String nextToken = null;

            do {
                DescribeInstanceTypesRequest request = DescribeInstanceTypesRequest.builder()
                        .maxResults(Math.min(limit, 100))
                        .nextToken(nextToken)
                        .build();
                DescribeInstanceTypesResponse response = client.describeInstanceTypes(request);
                for (InstanceTypeInfo instanceType : response.instanceTypes()) {
                    String name = instanceType.instanceTypeAsString();
                    if (!availableInRegion.contains(name)) {
                        // region 에서 launch 불가 — UI 노출 / validation 모두에서 제외.
                        continue;
                    }
                    VmOptionSpec dto = toSpecDto(region, instanceType);
                    if (!matchesKeyword(dto.getName(), keyword)) {
                        continue;
                    }
                    if (gpuOnly && Optional.ofNullable(dto.getGpuCount()).orElse(0) <= 0) {
                        continue;
                    }
                    results.add(dto);
                    if (results.size() >= limit) {
                        return results;
                    }
                }
                nextToken = response.nextToken();
            } while (StringUtils.hasText(nextToken));

            return results;
        } catch (RuntimeException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, "region", region, "Failed to query AWS VM specs: " + e.getMessage());
        }
    }

    /**
     * DescribeInstanceTypeOfferings (LocationType=region) 으로 region 전체에서 launch 가능한 instance
     * type names 의 합집합을 수집. Offerings 는 가벼운 API (per-page 1000) — 정상 region 은 1~2 page.
     */
    private Set<String> fetchAvailableInstanceTypeNames(Ec2Client client, String region) {
        Set<String> names = new HashSet<>();
        String nextToken = null;
        do {
            DescribeInstanceTypeOfferingsRequest request = DescribeInstanceTypeOfferingsRequest.builder()
                    .locationType(LocationType.REGION)
                    .filters(Filter.builder().name("location").values(region).build())
                    .maxResults(1000)
                    .nextToken(nextToken)
                    .build();
            DescribeInstanceTypeOfferingsResponse response = client.describeInstanceTypeOfferings(request);
            for (InstanceTypeOffering offering : response.instanceTypeOfferings()) {
                names.add(offering.instanceTypeAsString());
            }
            nextToken = response.nextToken();
        } while (StringUtils.hasText(nextToken));
        return names;
    }

    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    @Override
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        return doListImages(buildClient(region, null), region, keyword, architecture, owner, limit);
    }

    /** Credential-aware overload — fix. */
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listImagesFallback")
    @Override
    public List<VmOptionImage> listImages(
            Map<String, String> credentials,
            String region,
            String keyword,
            String architecture,
            String owner,
            int limit) {
        return doListImages(buildClient(region, credentials), region, keyword, architecture, owner, limit);
    }

    private List<VmOptionImage> doListImages(
            Ec2Client clientArg, String region, String keyword, String architecture, String owner, int limit) {
        try (Ec2Client client = clientArg) {
            List<Filter> filters = new ArrayList<>();
            filters.add(Filter.builder().name("state").values("available").build());
            filters.add(Filter.builder().name("is-public").values("true").build());
            if (StringUtils.hasText(keyword)) {
                filters.add(Filter.builder()
                        .name("name")
                        .values("*" + keyword + "*")
                        .build());
            }
            if (StringUtils.hasText(architecture)) {
                filters.add(Filter.builder()
                        .name("architecture")
                        .values(architecture)
                        .build());
            }

            DescribeImagesRequest request = DescribeImagesRequest.builder()
                    .owners(resolveOwners(owner))
                    .filters(filters)
                    .build();

            DescribeImagesResponse response = client.describeImages(request);
            return response.images().stream()
                    .sorted(Comparator.comparing(Image::creationDate, Comparator.nullsLast(String::compareTo))
                            .reversed())
                    .limit(limit)
                    .map(image -> toImageDto(region, image))
                    .toList();
        } catch (RuntimeException e) {
            throw new CustomException(
                    ErrorCode.RUNTIME_EXCEPTION, "region", region, "Failed to query AWS VM images: " + e.getMessage());
        }
    }

    private Region resolveRegion(String region) {
        if (!StringUtils.hasText(region)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "region", region, "AWS region is required");
        }
        return Region.of(region);
    }

    /**
     * Ec2Client builder — credentials map 이 있으면 명시 주입, 없으면 SDK default chain.
     *
     * <p>credentials map 형식 (Bruno 2b. AWS Credential Create 의 body 와 동일):
     * <pre>{@code
     *   { "AWS_ACCESS_KEY_ID": "AKIA...", "AWS_SECRET_ACCESS_KEY": "..." }
     * }</pre>
     *
     * <p>둘 중 하나라도 비어있으면 default chain 으로 fallback — env 변수 또는 EC2 instance role 활용.
     * 외부 deploy 환경에서는 default chain 으로 충분히 동작 (호스트 머신에 권한 부여).
     */
    private Ec2Client buildClient(String region, Map<String, String> credentials) {
        Ec2ClientBuilder builder = Ec2Client.builder()
                .region(resolveRegion(region))
                // Fail-fast: 잘못된 credential / 잘못된 region 으로 인한 retry 누적 차단.
                // SDK default 는 attempt timeout 무한 + retry 3+ → 가짜 cred 시 ~30s 이상.
                // numRetries(1) × attempt 3s = 최대 ~6s 안에 결정.
                .overrideConfiguration(software.amazon.awssdk.core.client.config.ClientOverrideConfiguration.builder()
                        .apiCallTimeout(java.time.Duration.ofSeconds(8))
                        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(3))
                        .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.builder()
                                .numRetries(1)
                                .build())
                        .build());
        AwsCredentialsProvider provider = toCredentialsProvider(credentials);
        if (provider != null) {
            builder.credentialsProvider(provider);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider toCredentialsProvider(Map<String, String> credentials) {
        if (credentials == null) {
            return null;
        }
        String accessKey = credentials.get("AWS_ACCESS_KEY_ID");
        String secretKey = credentials.get("AWS_SECRET_ACCESS_KEY");
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            return null;
        }
        // session token (STS) 도 지원 — backend 가 단기 credential 발급한 경우.
        String sessionToken = credentials.get("AWS_SESSION_TOKEN");
        if (StringUtils.hasText(sessionToken)) {
            return StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create(
                            accessKey, secretKey, sessionToken));
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private VmOptionSpec toSpecDto(String region, InstanceTypeInfo info) {
        int gpuCount = Optional.ofNullable(info.gpuInfo())
                .map(gpuInfo ->
                        gpuInfo.gpus().stream().mapToInt(gpu -> gpu.count()).sum())
                .orElse(0);

        String architecture =
                Optional.ofNullable(info.processorInfo())
                        .map(processorInfo -> processorInfo.supportedArchitectures())
                        .stream()
                        .flatMap(List::stream)
                        .map(arch -> arch.toString().toLowerCase(Locale.ROOT))
                        .findFirst()
                        .orElse(null);

        return VmOptionSpec.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(info.instanceTypeAsString())
                .name(info.instanceTypeAsString())
                .family(
                        info.instanceTypeAsString().contains(".")
                                ? info.instanceTypeAsString()
                                        .substring(
                                                0, info.instanceTypeAsString().indexOf('.'))
                                : info.instanceTypeAsString())
                .vcpu(Optional.ofNullable(info.vCpuInfo())
                        .map(vCpuInfo -> vCpuInfo.defaultVCpus())
                        .orElse(null))
                .memoryGb(Optional.ofNullable(info.memoryInfo())
                        .map(memoryInfo -> memoryInfo.sizeInMiB() / 1024.0)
                        .orElse(null))
                .gpuCount(gpuCount)
                .architecture(architecture)
                .description(info.hypervisor() == null ? null : "hypervisor=" + info.hypervisorAsString())
                .available(true)
                .build();
    }

    private VmOptionImage toImageDto(String region, Image image) {
        String imageName = image.name();
        return VmOptionImage.builder()
                .provider(getProvider().getCanonicalName())
                .region(region)
                .id(image.imageId())
                .name(imageName)
                .osType(inferOsType(imageName))
                .osVersion(inferOsVersion(imageName))
                .architecture(image.architectureAsString())
                .owner(image.ownerId())
                .visibility("public")
                .createdAt(image.creationDate())
                .build();
    }

    private List<String> resolveOwners(String owner) {
        if (!StringUtils.hasText(owner)) {
            return DEFAULT_OWNERS;
        }
        return Stream.of(owner.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
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
                || normalized.contains("amzn")
                || normalized.contains("amazon linux")
                || normalized.contains("rhel")
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
    // resilience4j 의 @CircuitBreaker 가 OPEN 또는 record-exception 발생 시 호출. 같은 인자
    // + 마지막 자리에 Throwable 파라미터. 빈 list 반환으로 UI 부분 가용성 유지 (5xx 폭증 회피).

    @SuppressWarnings("unused")
    private List<VmOptionSpec> listSpecsFallback(
            String region, String keyword, boolean gpuOnly, int limit, Throwable e) {
        // log 는 @CircuitBreaker 가 자체적으로 metric 으로 노출 — 여기서는 빈 결과만.
        return java.util.Collections.emptyList();
    }

    /** Credential-aware overload 의 fallback — Resilience4j 가 parameter type 매칭으로 선택. */
    @SuppressWarnings("unused")
    private List<VmOptionSpec> listSpecsFallback(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionImage> listImagesFallback(
            String region, String keyword, String architecture, String owner, int limit, Throwable e) {
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<VmOptionImage> listImagesFallback(
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
