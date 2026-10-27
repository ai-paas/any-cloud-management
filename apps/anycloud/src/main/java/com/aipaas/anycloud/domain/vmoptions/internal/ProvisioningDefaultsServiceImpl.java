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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** CSP 별로 현재 통과하는 생성 요청 조립. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningDefaultsServiceImpl implements ProvisioningDefaultsService {

    private static final String CONFIG_PREFIX = "anycloud-k8s:";

    private static final String PROVIDER_SPEC_PREFIX = CONFIG_PREFIX + "providerSpec.";

    private static final String OS_IMAGE_KEY = CONFIG_PREFIX + "osImage";

    /** 이미지 이름에 나타나는 비x86 아키텍처 표기. 하나라도 포함되면 기본값 후보에서 제외. */
    private static final java.util.List<String> NON_X86_MARKERS =
            java.util.List.of("arm", "aarch64", "s390x", "ppc64", "riscv");

    /** 스펙 목록 전수 조회 불필요. 하위 사양부터 일부만 조회. */
    private static final int SPEC_SCAN_LIMIT = 100;

    /** 존을 몇 개까지 두드릴지. 리전당 보통 서너 개다. */
    private static final int ZONE_SCAN_LIMIT = 6;

    /** 존 부재 타입의 건너뛰기 상한. 초과 조회 시 폼 로딩 지연. */
    private static final int SPEC_ZONE_ATTEMPTS = 8;

    /*
     * 캐시가 걸린 쪽을 쓴다. QueryService 를 직접 부르면 모달을 열 때마다 CSP API 를 전부 다시
     * 두드려 7종에 20초 넘게 걸렸다.
     */
    private final VmOptionsService vmOptionsService;
    private final CspCredentialRepository credentialRepository;
    private final CapacityProbe capacityProbe;

    /*
     * 요청마다 달라지는 조건. 필드로 두면 동시 요청끼리 값을 덮어씀. 호출 깊이가 얕아
     * ThreadLocal 대신 인자로 전달.
     */

    /**
     * @param provider 비우면 전체 CSP, 지정하면 해당 CSP 만 조회. 화면이 CSP 별로 나눠
     *     요청하는 경우 나머지 CSP 조회 비용을 피하기 위함
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
            // 한 CSP 조회 실패 시에도 나머지는 반환. 목록 전체가 비면 실패 원인 식별 불가.
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

        /*
         * Proxmox 는 cloud 이미지에 qemu-guest-agent 가 없어 주소를 물어볼 수 없다. 고정 주소가
         * 없으면 Pulumi 가 빈 주소 목록을 색인하다 죽는데, 그때는 VM 이 이미 만들어진 뒤다.
         */
        if (provider == SupportedProvisioningProvider.PROXMOX && !providerSpec.containsKey("nodeIps")) {
            return builder.ready(false)
                    .blockedReason("노드에 고정할 IP 를 지정해야 합니다 (providerSpec.nodeIps).")
                    .build();
        }

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

        CapacityProbe.SpecChoice spec = pickSpec(provider, credentialId, region, described, filter, Set.of());
        if (spec.blockedReason() != null) {
            return builder.ready(false).blockedReason(spec.blockedReason()).build();
        }
        /*
         * OpenStack 은 providerSpec.flavorName 이 우선이라 instanceType 무시. 두 값 불일치 시
         * 화면 표시 사양과 실제 생성 사양이 달라짐.
         */
        if (providerSpec.containsKey("flavorName")) {
            providerSpec.put("flavorName", spec.specId());
        }
        /*
         * 리전 제공 여부와 존별 제공 여부는 별개. 존을 선택하는 CSP 는 실제 생성 가능한 존으로
         * 치환. 미치환 시 기본값이 항상 실패하는 조합.
         */
        if (providerSpec.containsKey("zone")) {
            /*
             * 그 타입이 어느 존에도 없으면 다음 후보로 넘어간다. 한 번에 포기하면 리전에 쓸 수
             * 있는 타입이 있는데도 "만들 수 없다" 가 된다. Alibaba 서울은 ga1 이 없고 gn7i 가
             * 있는데, 후보가 작은 것부터라 ga1 이 먼저 잡힌다.
             */
            Set<String> tried = new java.util.LinkedHashSet<>();
            String usable = null;
            while (spec.specId() != null && tried.size() < SPEC_ZONE_ATTEMPTS) {
                usable = usableZone(provider, credentialId, region, schema, spec.specId());
                if (usable != null) {
                    break;
                }
                tried.add(spec.specId());
                spec = pickSpec(provider, credentialId, region, described, filter, tried);
            }
            if (usable == null) {
                return builder.ready(false)
                        .blockedReason("조건에 맞는 타입을 띄울 수 있는 존이 없습니다 (후보 " + tried.size() + "개 확인).")
                        .build();
            }
            providerSpec.put("zone", usable);
        }

        /*
         * control-plane 은 GPU 불필요. worker 와 같은 타입 적용 시 GPU 인스턴스 비용이 중복
         * 발생. GPU 인스턴스는 일반 타입 대비 10배 이상.
         */
        String masterSpec = spec.specId();
        if (filter.gpu()) {
            CapacityProbe.SpecChoice plain =
                    pickSpec(provider, credentialId, region, described, SpecFilter.DEFAULT, Set.of());
            if (plain.specId() != null) {
                masterSpec = plain.specId();
            }
        }
        return withSpecDetail(builder, provider, credentialId, region, spec.specId())
                .ready(true)
                .masterInstanceType(masterSpec)
                .workerInstanceType(spec.specId())
                .build();
    }

    /**
     * 권장 리전 우선 선택.
     *
     * <p>계정이 해당 리전을 구독하지 않은 경우가 있어 조회 결과에 존재하는지 확인 후 없으면
     * 첫 번째 리전 사용. 예: OCI 권장 리전은 서울이나 테넌시가 도쿄만 구독한 경우.
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
     * 선택한 타입의 vCPU, 메모리, GPU 수를 함께 반환.
     *
     * <p>타입 이름만으로는 사양을 알 수 없음. {@code bx2-2x8} 의 코어 수를 확인하려 CSP 콘솔을
     * 열어야 하면 기본값 제공 효과가 사라짐.
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
        // Proxmox 는 인스턴스 타입 목록 없음. 값 자체가 "코어-메모리MiB" 규약이므로 값에서 파싱.
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
     * 해당 타입을 생성할 수 있는 존 선택.
     *
     * <p>존 목록의 첫 값을 그대로 쓰면 a2-highgpu-1g 가 asia-northeast3-a 로, ecs.ga1.xlarge 가
     * ap-northeast-2a 로 지정되어 생성 단계에서 거절됨.
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

    /** 권장 스펙 우선. 해당 리전에 없으면 최소 사양 이상 중 최소 항목 선택. */
    private CapacityProbe.SpecChoice pickSpec(
            SupportedProvisioningProvider provider,
            String credentialId,
            String region,
            VmOptionProvider described,
            SpecFilter filter,
            Set<String> exclude) {
        String name = provider.getCanonicalName();
        String recommended = described == null ? null : described.getRecommendedVmSpec();

        // 권장값은 keyword 로 직접 조회. 목록 선두만 조회하면 권장값 미포함으로 매번 탈락.
        List<VmOptionSpec> matched = StringUtils.hasText(recommended)
                ? vmOptionsService.getSpecs(name, credentialId, region, recommended, false, SPEC_SCAN_LIMIT)
                : List.of();
        // 권장값도 필터 조건 통과 필요. GPU 요청 시 권장값이 일반 타입이면 사용 불가.
        String exact = matched.stream()
                .filter(spec -> !orderedCandidates(List.of(spec), filter).isEmpty())
                .map(this::specValue)
                .filter(value -> value != null && !exclude.contains(value))
                .filter(value -> value.equalsIgnoreCase(recommended))
                .findFirst()
                .orElse(null);
        List<VmOptionSpec> all = vmOptionsService.getSpecs(name, credentialId, region, null, false, SPEC_SCAN_LIMIT);
        if (all.isEmpty()) {
            /*
             * Proxmox 는 인스턴스 타입 목록이 없고 "코어-메모리MiB" 규약이라 권장값이 곧 값.
             * 필터 조건 검증 불가. GPU 요청에 권장값을 그대로 반환하면 GPU 없는 노드를
             * 준비됨으로 표시하게 됨.
             */
            if (filter.gpu()) {
                return new CapacityProbe.SpecChoice(null, "GPU 노드를 만들 수 없습니다 (인스턴스 타입 목록 없음).");
            }
            return StringUtils.hasText(recommended)
                    ? new CapacityProbe.SpecChoice(recommended, null)
                    : new CapacityProbe.SpecChoice(null, "고를 수 있는 인스턴스 타입이 없습니다.");
        }
        // 권장값을 선두에 두고 나머지도 후보로 유지. 권장값의 용량이 부족한 경우를 대비.
        List<String> candidates = new java.util.ArrayList<>();
        if (exact != null) {
            candidates.add(exact);
        }
        orderedCandidates(all, filter).stream()
                .filter(value -> !exclude.contains(value))
                .filter(value -> !value.equalsIgnoreCase(exact))
                .forEach(candidates::add);
        if (candidates.isEmpty()) {
            return new CapacityProbe.SpecChoice(
                    null,
                    (filter.gpu() ? "GPU 인스턴스가 없습니다" : "조건에 맞는 인스턴스 타입이 없습니다") + " (최소 " + filter.minVcpu() + "vCPU, "
                            + filter.minMemoryGb() + "GB).");
        }
        return capacityProbe.firstWithCapacity(provider, credentialId, region, candidates, filter);
    }

    /**
     * 최소 사양 이상 중 작은 순.
     *
     * <p>사양 조건 없이 가장 작은 값을 고르면 1vCPU 2GB 선택. kubeadm preflight 를 건너뛰도록
     * 설정해 두어 생성은 되지만 control-plane 이 정상 기동하지 않음.
     */
    private List<String> orderedCandidates(List<VmOptionSpec> specs, SpecFilter filter) {
        return specs.stream()
                .filter(spec -> specValue(spec) != null)
                .filter(spec -> spec.getVcpu() != null && spec.getVcpu() >= filter.minVcpu())
                .filter(spec -> spec.getMemoryGb() != null && spec.getMemoryGb() >= filter.minMemoryGb())
                // GPU 미요청 시 제외, 요청 시 GPU 탑재 항목만 유지.
                .filter(spec -> filter.gpu() == ((spec.getGpuCount() == null ? 0 : spec.getGpuCount()) > 0))
                .sorted(Comparator.comparing(VmOptionSpec::getVcpu).thenComparing(VmOptionSpec::getMemoryGb))
                .map(this::specValue)
                .distinct()
                .toList();
    }

    /**
     * 서버 전송 값.
     *
     * <p>OpenStack 은 {@code id} 가 UUID 이고 emitter 입력은 flavor 이름. 이름이 있으면 이름 사용.
     * AWS 처럼 두 값이 동일한 CSP 는 결과 동일.
     */
    private String specValue(VmOptionSpec spec) {
        return StringUtils.hasText(spec.getName()) ? spec.getName() : spec.getId();
    }

    /**
     * 같은 Ubuntu 중 검증된 버전 선택.
     *
     * <p>목록이 최신순이라 첫 값을 쓰면 출시 직후 릴리스가 선택됨. 부트스트랩이 설치하는
     * kubeadm 패키지가 해당 버전을 지원하지 않을 수 있음.
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
                .orElseGet(() -> options.stream()
                        .filter(option -> !isNonX86Image(option.label()))
                        .map(ConfigOption::value)
                        .findFirst()
                        .orElseGet(() -> options.get(0).value()));
    }

    /**
     * 기본 인스턴스 타입은 x86. 다른 아키텍처 이미지를 지정하면 생성 단계에서 거절된다.
     *
     * <p>OCI 는 이름에 {@code aarch64} 사용. {@code arm} 만 검사하면 걸러지지 않아 x86 shape 에
     * aarch64 이미지가 기본값으로 지정된 사례 있음. IBM 은 s390x 판을 함께 제공하는데
     * {@code ibm-ubuntu-24-04-4-minimal-s390x-2} 가 amd64 판보다 이름순으로 앞서 기본값이 되어
     * "Image OS architecture s390x is not supported" 로 실패.
     */
    private boolean isNonX86Image(String label) {
        if (!StringUtils.hasText(label)) {
            return false;
        }
        String normalized = label.toLowerCase(java.util.Locale.ROOT);
        return NON_X86_MARKERS.stream().anyMatch(normalized::contains);
    }

    /** {@code ubuntu-24.04}, {@code ubuntu_24_04}, {@code Ubuntu-24.04} 를 동일 값으로 처리. */
    private boolean matchesPreferredUbuntu(String label) {
        if (!StringUtils.hasText(label)) {
            return false;
        }
        String normalized =
                label.toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace('.', '-');
        return normalized.contains("ubuntu") && normalized.contains("24-04") && !isNonX86Image(label);
    }

    /**
     * 스키마 기본값보다 계정에서 조회되는 값을 우선.
     *
     * <p>OpenStack flavor 기본값 {@code m1.large} 는 설치본에 따라 존재 여부가 다름. 없는 값을
     * 전달하면 생성 중 거절됨.
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
