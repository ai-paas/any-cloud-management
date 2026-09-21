package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.ProvisioningDefaultsService;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.ConfigOption;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import com.aipaas.anycloud.domain.vmoptions.api.SpecFilter;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** CSP 마다 "지금 통과하는" 생성 요청을 조립한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningDefaultsServiceImpl implements ProvisioningDefaultsService {

    private static final String CONFIG_PREFIX = "anycloud-k8s:";

    private static final String PROVIDER_SPEC_PREFIX = CONFIG_PREFIX + "providerSpec.";

    private static final String OS_IMAGE_KEY = CONFIG_PREFIX + "osImage";

    /** 스펙 목록이 길어 전부 훑을 이유가 없다. 작은 것부터 몇 개만 본다. */
    private static final int SPEC_SCAN_LIMIT = 100;

    /** 존을 몇 개까지 두드릴지. 리전당 보통 서너 개다. */
    private static final int ZONE_SCAN_LIMIT = 6;

    /*
     * 캐시가 걸린 쪽을 쓴다. QueryService 를 직접 부르면 모달을 열 때마다 CSP API 를 전부 다시
     * 두드려 7종에 20초 넘게 걸렸다.
     */
    private final VmOptionsService vmOptionsService;
    private final CspCredentialRepository credentialRepository;
    private final CapacityProbe capacityProbe;

    /*
     * 요청마다 달라지는 조건이라 필드로 두면 동시 요청이 서로를 덮는다. 아래 메서드 체인에만
     * 쓰이므로 ThreadLocal 대신 인자로 넘긴다 — 여기서는 호출 깊이가 얕다.
     */

    /**
     * @param provider 비우면 전부. 하나만 주면 그 CSP 만 조회한다 — 화면이 CSP 별로 따로
     *     물어 한 줄씩 채우려면 나머지를 같이 풀면 안 된다
     */
    @Override
    public List<ProvisioningDefaults> listDefaults(String provider) {
        return listDefaults(provider, SpecFilter.DEFAULT);
    }

    @Override
    public List<ProvisioningDefaults> listDefaults(String provider, SpecFilter filter) {
        return resolveAll(provider, filter == null ? SpecFilter.DEFAULT : filter);
    }

    private List<ProvisioningDefaults> resolveAll(String provider, SpecFilter resolved) {
        Map<String, VmOptionProvider> catalog = new LinkedHashMap<>();
        for (VmOptionProvider described : vmOptionsService.getProviders()) {
            catalog.put(described.getProvider().toLowerCase(java.util.Locale.ROOT), described);
        }
        return java.util.Arrays.stream(SupportedProvisioningProvider.values())
                .filter(candidate -> !StringUtils.hasText(provider)
                        || candidate.getCanonicalName().equalsIgnoreCase(provider))
                .map(candidate -> resolve(candidate, catalog, resolved))
                .toList();
    }

    private ProvisioningDefaults resolve(
            SupportedProvisioningProvider provider, Map<String, VmOptionProvider> catalog, SpecFilter filter) {
        String name = provider.getCanonicalName();
        VmOptionProvider described = catalog.get(name.toLowerCase(java.util.Locale.ROOT));
        ProvisioningDefaults.ProvisioningDefaultsBuilder builder = ProvisioningDefaults.builder()
                .provider(name)
                .displayName(described == null ? name : described.getDisplayName());

        Optional<CspCredentialEntity> credential = pickCredential(name);
        if (credential.isEmpty()) {
            return builder.ready(false)
                    .blockedReason("등록된 " + name + " 자격증명이 없습니다.")
                    .build();
        }
        builder.credentialId(credential.get().getId())
                .credentialName(credential.get().getName());

        try {
            return fill(builder, provider, described, credential.get(), filter);
        } catch (RuntimeException e) {
            // 한 CSP 조회가 실패해도 나머지는 내려보낸다. 목록 전체가 비면 원인을 알 수 없다.
            log.warn("{} 기본값을 만들지 못했다: {}", name, e.toString());
            return builder.ready(false)
                    .blockedReason("조회에 실패했습니다: " + shortMessage(e))
                    .build();
        }
    }

    private ProvisioningDefaults fill(
            ProvisioningDefaults.ProvisioningDefaultsBuilder builder,
            SupportedProvisioningProvider provider,
            VmOptionProvider described,
            CspCredentialEntity credential,
            SpecFilter filter) {
        String name = provider.getCanonicalName();
        String credentialId = credential.getId();

        String region = pickRegion(name, credentialId, described);
        if (region == null) {
            return builder.ready(false).blockedReason("쓸 수 있는 리전이 없습니다.").build();
        }
        builder.region(region);

        List<ProviderConfigKey> schema = vmOptionsService.getConfigSchema(name, credentialId, region);
        Map<String, String> providerSpec = new LinkedHashMap<>();
        for (ProviderConfigKey key : schema) {
            if (!key.key().startsWith(PROVIDER_SPEC_PREFIX)) {
                continue;
            }
            String value = firstValue(key);
            if (value == null) {
                if (key.required()) {
                    return builder.ready(false)
                            .blockedReason((key.label() == null ? key.key() : key.label()) + " 에 고를 값이 없습니다.")
                            .build();
                }
                continue;
            }
            providerSpec.put(key.key().substring(PROVIDER_SPEC_PREFIX.length()), value);
        }
        builder.providerSpec(providerSpec);

        ProviderConfigKey image = schema.stream()
                .filter(k -> OS_IMAGE_KEY.equals(k.key()))
                .findFirst()
                .orElse(null);
        if (image != null) {
            String value = preferredImage(image);
            if (value == null && image.required()) {
                return builder.ready(false)
                        .blockedReason("고를 수 있는 OS 이미지가 없습니다.")
                        .build();
            }
            builder.osImage(value);
        }

        CapacityProbe.SpecChoice spec = pickSpec(provider, credentialId, region, described, filter);
        if (spec.blockedReason() != null) {
            return builder.ready(false).blockedReason(spec.blockedReason()).build();
        }
        /*
         * OpenStack 은 providerSpec.flavorName 이 우선이라 instanceType 이 무시된다. 두 값이
         * 갈리면 화면에 보이는 사양과 실제로 뜨는 사양이 달라진다.
         */
        if (providerSpec.containsKey("flavorName")) {
            providerSpec.put("flavorName", spec.specId());
        }
        /*
         * 리전에 있다고 모든 존에 있는 것이 아니다. 존을 고르는 CSP 는 실제로 뜨는 존으로
         * 바꿔 준다 — 그러지 않으면 기본값이 늘 실패하는 조합을 준다.
         */
        if (providerSpec.containsKey("zone")) {
            String usable = usableZone(provider, credentialId, region, schema, spec.specId());
            if (usable == null) {
                return builder.ready(false)
                        .blockedReason(spec.specId() + " 를 띄울 수 있는 존이 없습니다.")
                        .build();
            }
            providerSpec.put("zone", usable);
        }
        return withSpecDetail(builder, provider, credentialId, region, spec.specId())
                .ready(true)
                .masterInstanceType(spec.specId())
                .workerInstanceType(spec.specId())
                .build();
    }

    /**
     * 권장 리전을 먼저 본다.
     *
     * <p>계정이 그 리전을 구독하지 않았을 수 있어, 실제 조회 결과에 있는지 확인하고 없으면 첫
     * 번째로 내려간다 — OCI 권장은 서울인데 이 테넌시는 도쿄만 구독돼 있다.
     */
    private String pickRegion(String provider, String credentialId, VmOptionProvider described) {
        List<VmOptionRegion> regions = vmOptionsService.getRegions(provider, credentialId);
        if (regions.isEmpty()) {
            return null;
        }
        String recommended = described == null ? null : described.getRecommendedRegion();
        return regions.stream()
                .map(VmOptionRegion::getId)
                .filter(id -> id.equalsIgnoreCase(recommended))
                .findFirst()
                .orElseGet(() -> regions.get(0).getId());
    }

    /**
     * 고른 타입의 vCPU, 메모리, GPU 를 함께 싣는다.
     *
     * <p>타입 이름만 보고 크기를 아는 사람은 없다. {@code bx2-2x8} 이 몇 코어인지 확인하려고
     * 콘솔을 여는 순간, 값을 대신 골라 준 의미가 없어진다.
     */
    private ProvisioningDefaults.ProvisioningDefaultsBuilder withSpecDetail(
            ProvisioningDefaults.ProvisioningDefaultsBuilder builder,
            SupportedProvisioningProvider provider,
            String credentialId,
            String region,
            String specId) {
        String name = provider.getCanonicalName();
        VmOptionSpec detail =
                vmOptionsService.getSpecs(name, credentialId, region, specId, false, SPEC_SCAN_LIMIT).stream()
                        .filter(candidate -> specId.equalsIgnoreCase(specValue(candidate)))
                        .findFirst()
                        .orElse(null);
        if (detail != null) {
            return builder.vcpu(detail.getVcpu())
                    .memoryGb(detail.getMemoryGb())
                    .gpuCount(detail.getGpuCount() == null ? 0 : detail.getGpuCount());
        }
        // Proxmox 는 목록이 없다. 값 자체가 "코어-메모리MiB" 규약이라 거기서 읽는다.
        return proxmoxSpecDetail(builder, specId);
    }

    private ProvisioningDefaults.ProvisioningDefaultsBuilder proxmoxSpecDetail(
            ProvisioningDefaults.ProvisioningDefaultsBuilder builder, String specId) {
        String[] parts = specId == null ? new String[0] : specId.split("-");
        if (parts.length != 2) {
            return builder;
        }
        try {
            return builder.vcpu(Integer.parseInt(parts[0].trim()))
                    .memoryGb(Integer.parseInt(parts[1].trim()) / 1024.0)
                    .gpuCount(0);
        } catch (NumberFormatException e) {
            return builder;
        }
    }

    /**
     * 그 타입을 실제로 띄울 수 있는 존.
     *
     * <p>존 목록의 첫 값을 그냥 쓰면 a2-highgpu-1g 가 asia-northeast3-a 에, ecs.ga1.xlarge 가
     * ap-northeast-2a 에 잡혀 둘 다 생성 단계에서 거절된다.
     */
    private String usableZone(
            SupportedProvisioningProvider provider,
            String credentialId,
            String region,
            List<ProviderConfigKey> schema,
            String instanceType) {
        List<String> zones = schema.stream()
                .filter(key -> (PROVIDER_SPEC_PREFIX + "zone").equals(key.key()))
                .findFirst()
                .map(this::valuesOf)
                .orElse(List.of());
        for (String zone : zones.stream().limit(ZONE_SCAN_LIMIT).toList()) {
            if (vmOptionsService.isInstanceTypeAvailableInZone(
                    provider.getCanonicalName(), credentialId, region, zone, instanceType)) {
                return zone;
            }
        }
        return null;
    }

    private List<String> valuesOf(ProviderConfigKey key) {
        if (key.allowedOptions() != null && !key.allowedOptions().isEmpty()) {
            return key.allowedOptions().stream().map(ConfigOption::value).toList();
        }
        return key.allowedValues() == null ? List.of() : key.allowedValues();
    }

    /** 권장 스펙을 먼저 쓰고, 그 리전에 없으면 최소 사양을 넘는 것 중 가장 작은 것을 고른다. */
    private CapacityProbe.SpecChoice pickSpec(
            SupportedProvisioningProvider provider,
            String credentialId,
            String region,
            VmOptionProvider described,
            SpecFilter filter) {
        String name = provider.getCanonicalName();
        String recommended = described == null ? null : described.getRecommendedVmSpec();

        // 권장값은 keyword 로 직접 찾는다. 목록 앞쪽만 훑으면 권장값이 그 안에 없어 매번 밀린다.
        List<VmOptionSpec> matched = StringUtils.hasText(recommended)
                ? vmOptionsService.getSpecs(name, credentialId, region, recommended, false, SPEC_SCAN_LIMIT)
                : List.of();
        // 권장값도 조건을 만족해야 쓴다. GPU 를 요청했는데 권장값이 일반 타입이면 맞지 않는다.
        String exact = matched.stream()
                .filter(spec -> !orderedCandidates(List.of(spec), filter).isEmpty())
                .map(this::specValue)
                .filter(value -> value != null && value.equalsIgnoreCase(recommended))
                .findFirst()
                .orElse(null);
        List<VmOptionSpec> all = vmOptionsService.getSpecs(name, credentialId, region, null, false, SPEC_SCAN_LIMIT);
        if (all.isEmpty()) {
            /*
             * Proxmox 는 인스턴스 타입 목록이 없다 — "코어-메모리MiB" 규약이라 권장값이 곧 값이다.
             * 그래서 조건을 맞출 수도 없다. GPU 를 요청했는데 그냥 권장값을 주면 GPU 없는 노드를
             * 준비됨으로 보여주게 된다.
             */
            if (filter.gpu()) {
                return new CapacityProbe.SpecChoice(null, "GPU 노드를 만들 수 없습니다 (인스턴스 타입 목록 없음).");
            }
            return StringUtils.hasText(recommended)
                    ? new CapacityProbe.SpecChoice(recommended, null)
                    : new CapacityProbe.SpecChoice(null, "고를 수 있는 인스턴스 타입이 없습니다.");
        }
        // 권장값을 맨 앞에 두되 나머지도 남긴다. 권장값에 자리가 없을 때 그냥 포기하면 안 된다.
        List<String> candidates = new java.util.ArrayList<>();
        if (exact != null) {
            candidates.add(exact);
        }
        orderedCandidates(all, filter).stream()
                .filter(value -> !value.equalsIgnoreCase(exact))
                .forEach(candidates::add);
        if (candidates.isEmpty()) {
            return new CapacityProbe.SpecChoice(
                    null,
                    (filter.gpu() ? "GPU 인스턴스가 없습니다" : "조건에 맞는 인스턴스 타입이 없습니다") + " (최소 " + filter.minVcpu() + "vCPU, "
                            + filter.minMemoryGb() + "GB).");
        }
        return capacityProbe.firstWithCapacity(provider, credentialId, region, candidates);
    }

    /**
     * 최소 사양을 넘는 것 중 작은 것부터.
     *
     * <p>가장 작은 것을 그냥 고르면 1vCPU 2GB 가 잡힌다 — kubeadm preflight 를 건너뛰게 해둬서
     * 생성은 되지만 control-plane 이 제대로 뜨지 않는다.
     */
    private List<String> orderedCandidates(List<VmOptionSpec> specs, SpecFilter filter) {
        return specs.stream()
                .filter(spec -> specValue(spec) != null)
                .filter(spec -> spec.getVcpu() != null && spec.getVcpu() >= filter.minVcpu())
                .filter(spec -> spec.getMemoryGb() != null && spec.getMemoryGb() >= filter.minMemoryGb())
                // GPU 를 원하지 않으면 제외하고, 원하면 GPU 가 붙은 것만 남긴다.
                .filter(spec -> filter.gpu() == ((spec.getGpuCount() == null ? 0 : spec.getGpuCount()) > 0))
                .sorted(Comparator.comparing(VmOptionSpec::getVcpu).thenComparing(VmOptionSpec::getMemoryGb))
                .map(this::specValue)
                .distinct()
                .toList();
    }

    /**
     * 서버로 보낼 값.
     *
     * <p>OpenStack 은 {@code id} 가 UUID 이고 emitter 가 받는 것은 flavor 이름이다. 이름이 있으면
     * 이름을 쓴다 — AWS 처럼 둘이 같은 CSP 는 어느 쪽을 써도 결과가 같다.
     */
    private String specValue(VmOptionSpec spec) {
        return StringUtils.hasText(spec.getName()) ? spec.getName() : spec.getId();
    }

    /**
     * 같은 Ubuntu 라도 검증한 버전을 고른다.
     *
     * <p>목록은 최신순이라 그냥 첫 값을 쓰면 갓 나온 릴리스가 잡힌다. 부트스트랩이 설치하는
     * kubeadm 패키지가 그 버전에 올라와 있다는 보장이 없다.
     */
    private String preferredImage(ProviderConfigKey key) {
        List<ConfigOption> options = key.allowedOptions();
        if (options == null || options.isEmpty()) {
            return firstValue(key);
        }
        return options.stream()
                .filter(option -> matchesPreferredUbuntu(option.label()))
                .map(ConfigOption::value)
                .findFirst()
                .orElseGet(() -> options.get(0).value());
    }

    /** {@code ubuntu-24.04}, {@code ubuntu_24_04}, {@code Ubuntu-24.04} 를 모두 같은 것으로 본다. */
    private boolean matchesPreferredUbuntu(String label) {
        if (!StringUtils.hasText(label)) {
            return false;
        }
        String normalized =
                label.toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace('.', '-');
        return normalized.contains("ubuntu") && normalized.contains("24-04") && !normalized.contains("arm");
    }

    /**
     * 스키마 기본값보다 계정에서 실제로 고를 수 있는 값이 우선이다.
     *
     * <p>OpenStack flavor 기본값 {@code m1.large} 는 설치본마다 있을 수도 없을 수도 있다. 없는
     * 값을 그대로 보내면 생성 도중 거절된다.
     */
    private String firstValue(ProviderConfigKey key) {
        List<ConfigOption> options = key.allowedOptions();
        List<String> values = options != null && !options.isEmpty()
                ? options.stream().map(ConfigOption::value).toList()
                : (key.allowedValues() == null ? List.<String>of() : key.allowedValues());
        String fallback = key.defaultValue();
        if (values.isEmpty()) {
            return StringUtils.hasText(fallback) ? fallback : null;
        }
        return values.stream()
                .filter(value -> value.equalsIgnoreCase(fallback))
                .findFirst()
                .orElseGet(() -> values.get(0));
    }

    /** 확인된 자격증명을 먼저 쓴다. 상태를 모르는 것보다 통과한 것이 성공 확률이 높다. */
    private Optional<CspCredentialEntity> pickCredential(String provider) {
        List<CspCredentialEntity> all = credentialRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> provider.equalsIgnoreCase(c.getProvider()))
                .toList();
        return all.stream()
                .filter(c -> "HEALTHY".equalsIgnoreCase(String.valueOf(c.getHealthStatus())))
                .findFirst()
                .or(() -> all.stream().findFirst());
    }

    private String shortMessage(RuntimeException e) {
        String message = e.getMessage();
        if (!StringUtils.hasText(message)) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 120 ? message : message.substring(0, 120);
    }
}
