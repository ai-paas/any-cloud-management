package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProperties;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class VmOptionsQueryServiceImpl implements com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService {

    private final Map<SupportedProvisioningProvider, VmOptionsProvider> providers;
    private final VmOptionsProperties properties;
    private final CspCredentialService cspCredentialService;
    private final CspCredentialRepository cspCredentialRepository;

    public VmOptionsQueryServiceImpl(
            List<VmOptionsProvider> providerImplementations,
            VmOptionsProperties properties,
            CspCredentialService cspCredentialService,
            CspCredentialRepository cspCredentialRepository) {
        this.properties = properties;
        this.cspCredentialService = cspCredentialService;
        this.cspCredentialRepository = cspCredentialRepository;
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
                    case AZURE -> equalsIgnoreCase(spec.getName(), "Standard_D4s_v5");
                    case OPENSTACK -> equalsIgnoreCase(spec.getName(), "m1.large");
                    case ALIBABA -> equalsIgnoreCase(spec.getName(), "ecs.g6.large");
                    case OCI -> equalsIgnoreCase(spec.getName(), "VM.Standard.E4.Flex");
                    case DIGITALOCEAN -> equalsIgnoreCase(spec.getName(), "s-2vcpu-4gb");
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
                    case AZURE -> containsIgnoreCase(image.getName(), "ubuntu")
                            && containsIgnoreCase(image.getName(), "24.04");
                    case ALIBABA, OCI, DIGITALOCEAN -> containsIgnoreCase(image.getName(), "ubuntu");
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
