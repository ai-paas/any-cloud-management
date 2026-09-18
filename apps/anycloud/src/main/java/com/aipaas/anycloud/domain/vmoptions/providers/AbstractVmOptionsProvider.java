package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public abstract class AbstractVmOptionsProvider implements VmOptionsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractVmOptionsProvider.class);

    /**
     * Per-thread credential override 	 *
     * <p>Credential-aware overload (listSpecs/listImages/listRegions) 가 본 ThreadLocal 에 caller 의
     * decrypted credential map 을 set → 그 안에서 no-cred 변형이 실행됨 → 그 변형이 사용하는
     * {@link #resolveCredential(String)} 가 본 map 을 보고 없으면 {@code System.getenv()} fallback.
     *
     * <p>이 패턴 덕분에 각 provider 는 credentials 인자를 method signature 에 thread 할 필요 없이
     * "{@code resolveCredential("KEY")}" 한 줄로 사용자 등록 credential 을 활용.
     */
    private static final ThreadLocal<Map<String, String>> CREDENTIAL_CONTEXT = new ThreadLocal<>();

    @Override
    public List<VmOptionRegion> listRegions() {
        return Collections.emptyList();
    }

    @Override
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        throw unsupported("specs", region);
    }

    @Override
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        throw unsupported("images", region);
    }

    // ---- credential-aware overloads (ThreadLocal 주입) -------------------------------------

    @Override
    public List<VmOptionRegion> listRegions(Map<String, String> credentials) {
        return withCredentials(credentials, this::listRegions);
    }

    @Override
    public List<VmOptionSpec> listSpecs(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit) {
        return withCredentials(credentials, () -> listSpecs(region, keyword, gpuOnly, limit));
    }

    @Override
    public List<VmOptionImage> listImages(
            Map<String, String> credentials,
            String region,
            String keyword,
            String architecture,
            String owner,
            int limit) {
        return withCredentials(credentials, () -> listImages(region, keyword, architecture, owner, limit));
    }

    @Override
    public List<String> listConfigOptions(Map<String, String> credentials, String configKey, String region) {
        return withCredentials(credentials, () -> listConfigOptions(configKey, region));
    }

    /**
     * credentials 를 ThreadLocal 에 잠시 set 한 채로 body 실행 — 종료 시 이전 값 복구.
     * try/finally 로 nested call 안전.
     */
    protected static <T> T withCredentials(Map<String, String> credentials, Supplier<T> body) {
        Map<String, String> previous = CREDENTIAL_CONTEXT.get();
        try {
            if (credentials != null && !credentials.isEmpty()) {
                CREDENTIAL_CONTEXT.set(credentials);
            } else {
                CREDENTIAL_CONTEXT.remove();
            }
            return body.get();
        } finally {
            if (previous != null) {
                CREDENTIAL_CONTEXT.set(previous);
            } else {
                CREDENTIAL_CONTEXT.remove();
            }
        }
    }

    /**
     * Provider 별 helper 가 {@code System.getenv("KEY")} 대신 본 method 호출 — 사용자가 등록한 credential
     * 우선, 없으면 process env 로 fallback. blank 도 missing 으로 treat.
     */
    protected static String resolveCredential(String key) {
        Map<String, String> creds = CREDENTIAL_CONTEXT.get();
        if (creds != null) {
            String v = creds.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return System.getenv(key);
    }

    /**
     * 지금 요청에 실린 자격증명 전체.
     *
     * <p>키를 하나씩 {@link #resolveCredential(String)} 로 꺼내 Map 을 다시 만들 필요가 없다.
     * 자격증명 Map 을 통째로 받는 클라이언트({@code ProxmoxApiClient} 등) 에 그대로 넘긴다.
     *
     * <p>등록된 자격증명이 없으면 빈 Map — {@code System.getenv()} fallback 은 여기 없다.
     * 키 단위 fallback 은 어떤 키가 필요한지 아는 쪽이 정한다.
     */
    protected static Map<String, String> currentCredentials() {
        Map<String, String> creds = CREDENTIAL_CONTEXT.get();
        return creds == null ? Map.of() : Map.copyOf(creds);
    }

    protected VmOptionProvider describe(
            SupportedProvisioningProvider provider, boolean liveDiscoveryImplemented, String notes) {
        return VmOptionProvider.builder()
                .provider(provider.getCanonicalName())
                .displayName(provider.getCanonicalName())
                .supportsRegions(true)
                .supportsInstanceTypes(true)
                .supportsImages(true)
                .liveDiscoveryImplemented(liveDiscoveryImplemented)
                .recommendedRegion(defaultRecommendedRegion(provider))
                .recommendedVmSpec(defaultRecommendedVmSpec(provider))
                .recommendedOsImage(defaultRecommendedOsImage(provider))
                .defaultWorkerCount(2)
                .defaultKubernetesVersion("1.31")
                .notes(notes)
                .build();
    }

    private String defaultRecommendedRegion(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case AWS -> "ap-northeast-2";
            case GCP -> "asia-northeast3";
            case OPENSTACK -> "RegionOne";
            case ALIBABA -> "ap-northeast-2";
            case OCI -> "ap-seoul-1";
                // Proxmox 는 리전 개념이 없다. PVE 노드 이름을 쓴다.
            case PROXMOX -> "pve";
            case IBM -> "jp-tok";
        };
    }

    private String defaultRecommendedVmSpec(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case AWS -> "t3.large";
            case GCP -> "e2-standard-2";
            case OPENSTACK -> "m1.large";
            case ALIBABA -> "ecs.g9i.large";
            case OCI -> "VM.Standard.E4.Flex";
                // Proxmox 는 인스턴스 타입이 없다. "코어-메모리MiB" 규약.
            case PROXMOX -> "2-4096";
            case IBM -> "bx2-2x8";
        };
    }

    private String defaultRecommendedOsImage(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case AWS, OPENSTACK -> "ubuntu-24.04";
            case GCP -> "ubuntu-2404-lts";
            case ALIBABA, OCI -> "Ubuntu 24.04";
            case PROXMOX -> "ubuntu-24.04-server-cloudimg-amd64.img";
            case IBM -> "ibm-ubuntu-24-04-4-minimal-amd64-7";
        };
    }

    protected CustomException unsupported(String capability, String region) {
        return new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                capability,
                region,
                getProvider().getCanonicalName() + " VM options discovery is not implemented yet");
    }

    // ---- SSRF 방어 ----------------------------------------------

    /** CSP region id 허용 문자. 소문자, 숫자, 하이픈만. */
    private static final java.util.regex.Pattern REGION_ID =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

    /**
     * region id 를 URL 에 넣기 전에 검증한다. blank 는 통과시킨다 — 호출부가 blank 를
     * "지정 안 함"으로 처리해 기본 endpoint 나 기본 region 으로 분기하기 때문이다.
     *
     * @throws CustomException 형식에 맞지 않는 경우
     */
    protected String requireValidRegionId(String regionId) {
        if (!StringUtils.hasText(regionId)) {
            return regionId;
        }
        String normalized = regionId.toLowerCase(Locale.ROOT);
        if (!REGION_ID.matcher(normalized).matches()) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, "region", regionId, "region id 는 소문자, 숫자, 하이픈만 사용할 수 있습니다");
        }
        return normalized;
    }

    /**
     * URL path 에 끼워 넣을 식별자(GCP project id 등) 를 검증한다.
     *
     * @throws CustomException 형식에 맞지 않는 경우
     */
    protected String requireValidPathSegment(String name, String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (!REGION_ID.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, name, value, name + " 은 소문자, 숫자, 하이픈만 사용할 수 있습니다");
        }
        return value;
    }

    /**
     * 조립한 URL 의 host 가 기대한 값과 정확히 일치하는지 확인한다.
     *
     * @throws CustomException host 가 기대한 값과 다른 경우
     */
    protected String requireExpectedHost(String url, String expectedHost) {
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "endpoint", url, "endpoint URL 을 해석할 수 없습니다");
        }
        if (!expectedHost.equalsIgnoreCase(host)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "endpoint", host, "허용되지 않은 endpoint host 입니다");
        }
        return url;
    }

    /**
     * 응답 본문에서 받은 URL(pagination link 등) 이 기대한 host 인지 확인한다.
     *
     * <p>이 URL 로 다시 요청할 때 access token 이 함께 붙으므로, host 를 확인하지 않으면
     * 응답을 조작할 수 있는 상대에게 token 을 넘겨주는 경로가 된다.
     *
     * @return host 가 일치하면 {@code url}, 아니면 {@code null} (호출부가 페이징 중단)
     */
    protected String sameHostOrNull(String url, String expectedHost) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            String host = java.net.URI.create(url).getHost();
            if (expectedHost.equalsIgnoreCase(host)) {
                return url;
            }
            LOG.warn("예상과 다른 host 의 link 를 무시: expected={}, actual={}", expectedHost, host);
        } catch (IllegalArgumentException e) {
            LOG.warn("파싱할 수 없는 link 를 무시: {}", e.getMessage());
        }
        return null;
    }

    // ---- 공통 필터 / fallback helper -----------------------------

    /** keyword 기반 case-insensitive contains 매칭. 9 개 provider (AWS/Azure/GCP/OCI/Alibaba/OpenStack/DigitalOcean/Curated) 가 동일 8 줄을 각자 보유하던 패턴을 본 부모 메서드로 통합 — {@code keyword} 가 blank 면 모두 통과, {@code value} 가 null 이면 false. */
    protected static boolean matchesKeyword(String value, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * Circuit breaker fallback 의 표준 응답 — 빈 region 리스트. 9 provider 의 listRegionsFallback
     * 본문이 모두 동일했으므로 본 helper 로 한 줄 위임 가능:
     *
     * <pre>
     *   private List&lt;VmOptionRegion&gt; listRegionsFallback(Throwable e) {
     *       return emptyRegionsFallback("Provider", e);
     *   }
     * </pre>
     *
     * <p>{@code providerLabel} 은 log 에 표시될 식별자 (보통 {@code getProvider().getCanonicalName()}).
     * {@code throwable} 의 message 를 함께 warn 로 남겨 circuit OPEN 사유 추적 가능.
     */
    protected List<VmOptionRegion> emptyRegionsFallback(String capability, Throwable throwable) {
        logFallback("regions", capability, throwable);
        return Collections.emptyList();
    }

    /** 동일 패턴 — specs 응답의 빈 fallback. */
    protected List<VmOptionSpec> emptySpecsFallback(String capability, Throwable throwable) {
        logFallback("specs", capability, throwable);
        return Collections.emptyList();
    }

    /** 동일 패턴 — images 응답의 빈 fallback. */
    protected List<VmOptionImage> emptyImagesFallback(String capability, Throwable throwable) {
        logFallback("images", capability, throwable);
        return Collections.emptyList();
    }

    /** Circuit breaker fallback 로깅. stderr 는 운영 환경의 모니터링 도구가 놓칠 수 있으므로 SLF4J WARN 으로 통일 — circuit OPEN 사유 추적용. 대량 발생은 resilience4j.circuitbreaker.* Prometheus metric 로 별도 알람. */
    private void logFallback(String resource, String capability, Throwable throwable) {
        String msg = throwable == null ? "<unknown>" : String.valueOf(throwable.getMessage());
        LOG.warn(
                "VmOptionsProvider fallback: provider={} resource={} capability={} cause={}",
                getProvider().getCanonicalName(),
                resource,
                capability,
                msg);
    }
}
