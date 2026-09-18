package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.ProviderConfigSchemaService;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProperties;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProvider;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class VmOptionsQueryServiceImpl implements com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService {

    private static final String CONFIG_PREFIX = "anycloud-k8s:";

    private static final String OS_IMAGE_KEY = "osImage";

    /** 노드는 Ubuntu 위에 kubeadm 을 올린다. 다른 배포판을 고르면 부트스트랩이 깨진다. */
    private static final String UBUNTU_KEYWORD = "ubuntu";

    /** 목록이 길면 화면에서 고르기 어렵다. 최신 몇 개면 충분하다. */
    private static final int OS_IMAGE_LIMIT = 30;

    private final Map<SupportedProvisioningProvider, VmOptionsProvider> providers;
    private final VmOptionsProperties properties;
    private final CspCredentialService cspCredentialService;
    private final CspCredentialRepository cspCredentialRepository;
    private final ProviderConfigSchemaService providerConfigSchemaService;

    public VmOptionsQueryServiceImpl(
            List<VmOptionsProvider> providerImplementations,
            VmOptionsProperties properties,
            CspCredentialService cspCredentialService,
            CspCredentialRepository cspCredentialRepository,
            ProviderConfigSchemaService providerConfigSchemaService) {
        this.properties = properties;
        this.cspCredentialService = cspCredentialService;
        this.cspCredentialRepository = cspCredentialRepository;
        this.providerConfigSchemaService = providerConfigSchemaService;
        this.providers = new EnumMap<>(SupportedProvisioningProvider.class);
        for (VmOptionsProvider providerImplementation : providerImplementations) {
            this.providers.put(providerImplementation.getProvider(), providerImplementation);
        }
    }

    /**
     * credentialId 가 주어지면 해당 credential 을 decrypt 하여 provider 에 전달, 없으면 빈 map 반환.
     * 빈 map 을 받은 provider 는 자체적으로 환경변수에서 키를 읽는 default 동작으로 fallback.
     *
     * <p>credentialId 가 invalid (DB 미존재 / provider 불일치 / required 키 누락) 면 throw —
     * 사용자가 잘못된 credentialId 를 보냈을 때 빠르게 알려야 한다 (silent fallback 보다 fail-fast).
     */
    private Map<String, String> resolveCredentials(String provider, String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return Map.of();
        }
        cspCredentialRepository
                .findById(credentialId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.NOT_FOUND, "credentialId", credentialId, "Credential not found: " + credentialId));
        return cspCredentialService.resolveEnvironment(provider, credentialId);
    }

    @Override
    public List<VmOptionProvider> listProviders() {
        return providers.values().stream()
                .map(VmOptionsProvider::describe)
                .sorted(Comparator.comparing(VmOptionProvider::getDisplayName))
                .toList();
    }

    @Override
    public List<ProviderConfigKey> listConfigSchema(String provider, String credentialId, String region) {
        List<ProviderConfigKey> schema = providerConfigSchemaService.getSchema(provider);
        if (!StringUtils.hasText(credentialId)) {
            return schema;
        }
        Map<String, String> creds = resolveCredentials(provider, credentialId);
        VmOptionsProvider vmOptionsProvider = resolve(provider);
        return schema.stream()
                .map(key -> withOptions(vmOptionsProvider, creds, key, region))
                .toList();
    }

    /**
     * 이미지는 조회 경로가 따로 있어 선택지가 비어 있었다. 필수인데 고를 값이 없어 사용자가
     * OCID 나 빌드 날짜가 붙은 ID 를 직접 받아 적어야 했다.
     *
     * <p>{@code id} 를 쓴다 — emitter 가 그대로 CSP 에 넘기는 값이다.
     */
    private List<String> osImageOptions(VmOptionsProvider provider, Map<String, String> creds, String region) {
        return provider.listImages(creds, region, UBUNTU_KEYWORD, null, null, OS_IMAGE_LIMIT).stream()
                .map(VmOptionImage::getId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /** 이미 허용값이 적힌 키는 그대로 둔다. 정적 제약을 조회 결과로 덮으면 안 된다. */
    private ProviderConfigKey withOptions(
            VmOptionsProvider vmOptionsProvider, Map<String, String> creds, ProviderConfigKey key, String region) {
        if (key.allowedValues() != null && !key.allowedValues().isEmpty()) {
            return key;
        }
        String shortKey = key.key().startsWith(CONFIG_PREFIX) ? key.key().substring(CONFIG_PREFIX.length()) : key.key();
        List<String> options;
        try {
            options = OS_IMAGE_KEY.equals(shortKey)
                    ? osImageOptions(vmOptionsProvider, creds, region)
                    : vmOptionsProvider.listConfigOptions(creds, shortKey, region);
        } catch (RuntimeException e) {
            /*
             * 조회 하나가 실패해도 스키마는 내려보낸다. 예외를 올리면 폼이 전부 비어
             * "이 CSP 는 설정할 것이 없다"로 보인다 — OpenStack 이 그렇게 15개 필드를 통째로 잃었다.
             */
            log.warn("설정 선택지를 채우지 못했다 key={} region={}: {}", key.key(), region, e.toString());
            return key;
        }
        if (options.isEmpty()) {
            return key;
        }
        return ProviderConfigKey.builder()
                .key(key.key())
                .type(key.type())
                .required(key.required())
                .defaultValue(key.defaultValue())
                .description(key.description())
                .allowedValues(options)
                .build();
    }

    @Override
    public List<VmOptionRegion> listRegions(String provider, String credentialId) {
        Map<String, String> creds = resolveCredentials(provider, credentialId);
        return resolve(provider).listRegions(creds);
    }

    @Override
    public List<VmOptionSpec> listSpecs(
            String provider, String credentialId, String region, String keyword, Boolean gpuOnly, Integer limit) {
        SupportedProvisioningProvider normalized = normalizeProvider(provider);
        Map<String, String> creds = resolveCredentials(provider, credentialId);
        return resolve(normalized)
                .listSpecs(creds, region, keyword, Boolean.TRUE.equals(gpuOnly), normalizeLimit(limit))
                .stream()
                .map(spec -> applyRecommendedSpec(normalized, spec))
                .toList();
    }

    @Override
    public List<VmOptionImage> listImages(
            String provider,
            String credentialId,
            String region,
            String keyword,
            String architecture,
            String owner,
            Integer limit) {
        SupportedProvisioningProvider normalized = normalizeProvider(provider);
        Map<String, String> creds = resolveCredentials(provider, credentialId);
        return resolve(normalized)
                .listImages(
                        creds,
                        region,
                        keyword,
                        normalizeArchitecture(architecture),
                        normalizeOwner(owner),
                        normalizeLimit(limit))
                .stream()
                .map(image -> applyRecommendedImage(normalized, image))
                .toList();
    }

    private String normalizeArchitecture(String architecture) {
        if (architecture == null || architecture.isBlank()) {
            return architecture;
        }
        return switch (architecture.trim().toLowerCase()) {
            case "amd64", "x64", "x86-64" -> "x86_64";
            case "arm", "aarch64" -> "arm64";
            default -> architecture.trim();
        };
    }

    private String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return owner;
        }
        return owner.trim();
    }

    private VmOptionsProvider resolve(String provider) {
        return resolve(normalizeProvider(provider));
    }

    private VmOptionsProvider resolve(SupportedProvisioningProvider normalized) {
        VmOptionsProvider vmOptionsProvider = providers.get(normalized);
        if (vmOptionsProvider == null) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "provider",
                    normalized.getCanonicalName(),
                    "VM options provider is not registered");
        }
        return vmOptionsProvider;
    }

    private SupportedProvisioningProvider normalizeProvider(String provider) {
        try {
            return SupportedProvisioningProvider.from(provider);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "provider", provider, e.getMessage());
        }
    }

    private int normalizeLimit(Integer limit) {
        int resolved = limit == null ? properties.getDefaultLimit() : limit;
        if (resolved <= 0) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE, "limit", String.valueOf(limit), "Limit must be greater than zero");
        }
        return Math.min(resolved, properties.getMaxLimit());
    }

    private VmOptionSpec applyRecommendedSpec(SupportedProvisioningProvider provider, VmOptionSpec spec) {
        boolean recommended =
                switch (provider) {
                    case AWS -> equalsIgnoreCase(spec.getName(), "t3.large");
                    case GCP -> equalsIgnoreCase(spec.getName(), "e2-standard-2");
                    case OPENSTACK -> equalsIgnoreCase(spec.getName(), "m1.large");
                    case ALIBABA -> equalsIgnoreCase(spec.getName(), "ecs.g9i.large");
                    case OCI -> equalsIgnoreCase(spec.getName(), "VM.Standard.E4.Flex");
                    case PROXMOX -> equalsIgnoreCase(spec.getName(), "2-4096");
                    case IBM -> equalsIgnoreCase(spec.getName(), "bx2-2x8");
                };

        return VmOptionSpec.builder()
                .provider(spec.getProvider())
                .region(spec.getRegion())
                .id(spec.getId())
                .name(spec.getName())
                .family(spec.getFamily())
                .vcpu(spec.getVcpu())
                .memoryGb(spec.getMemoryGb())
                .gpuCount(spec.getGpuCount())
                .architecture(spec.getArchitecture())
                .description(spec.getDescription())
                .available(spec.getAvailable())
                .recommended(recommended)
                .recommendationReason(recommended ? "PoC용 기본 VM 스펙" : null)
                .build();
    }

    private VmOptionImage applyRecommendedImage(SupportedProvisioningProvider provider, VmOptionImage image) {
        boolean recommended =
                switch (provider) {
                    case AWS, OPENSTACK -> containsIgnoreCase(image.getName(), "ubuntu")
                            && containsIgnoreCase(image.getName(), "24.04");
                    case GCP -> containsIgnoreCase(image.getName(), "ubuntu")
                            && containsIgnoreCase(image.getName(), "2404");
                    case ALIBABA, OCI, PROXMOX, IBM -> containsIgnoreCase(image.getName(), "ubuntu");
                };

        return VmOptionImage.builder()
                .provider(image.getProvider())
                .region(image.getRegion())
                .id(image.getId())
                .name(image.getName())
                .osType(image.getOsType())
                .osVersion(image.getOsVersion())
                .architecture(image.getArchitecture())
                .owner(image.getOwner())
                .visibility(image.getVisibility())
                .createdAt(image.getCreatedAt())
                .recommended(recommended)
                .recommendationReason(recommended ? "kubeadm 기반 VM 클러스터 기본 이미지" : null)
                .build();
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        return value != null && fragment != null && value.toLowerCase().contains(fragment.toLowerCase());
    }
}
