package com.aipaas.anycloud.domain.vmoptions.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class VmOptionsSelectionValidator {

    /*
     * 캐시 적용 경로 사용. QueryService 직접 호출 시 preflight 마다 CSP API 를 200건씩 재조회해
     * 생성 1회당 수백 건 발생.
     */
    private final VmOptionsService vmOptionsService;

    /*
     * CSP 별 아키텍처 표기 차이 흡수. IBM 은 amd64 / s390x, AWS 는 x86_64 / arm64,
     * Alibaba 는 X86 / ARM 사용. 표기만 다르고 같은 아키텍처인 경우를 불일치로 오판하면
     * 정상 조합이 차단됨. 표에 없는 값은 canonical 이 null 이고 검사 생략 대상.
     */
    private static final Map<String, String> ARCHITECTURE_ALIASES = Map.ofEntries(
            Map.entry("x86_64", "amd64"),
            Map.entry("x86-64", "amd64"),
            Map.entry("x86", "amd64"),
            Map.entry("amd64", "amd64"),
            Map.entry("aarch64", "arm64"),
            Map.entry("arm64", "arm64"),
            Map.entry("arm", "arm64"),
            Map.entry("s390x", "s390x"),
            Map.entry("ppc64le", "ppc64le"));

    public VmOptionsSelectionValidator(VmOptionsService vmOptionsService) {
        this.vmOptionsService = vmOptionsService;
    }

    /**
     * @param credentialId 사용자가 등록한 CSP credential 의 UUID. null/blank 면 backend host 의 env
     *                     변수 / IAM role 로 fallback (보통 실패하므로 명시 전달 권장).
     *
     * <p> fix: 이전에는 credentialId 를 받지 않아 live spec 조회가 backend host 의 default
     * credential 로 진행 → AWS 같이 host 에 credential 이 없는 환경에서 빈 list 반환 → 모든
     * 선택이 "not found" 로 잘못 표시됨.
     */
    public void validateSelections(String provider, String credentialId, String region, Map<String, String> config) {
        if ("Proxmox".equalsIgnoreCase(provider)) {
            /*
             * Proxmox 는 인스턴스 타입 개념이 없음. "코어-메모리MiB" 를 사용자가 직접 지정하므로
             * 대조할 목록이 없고, 진행해도 빈 목록과 "조회 불가" 경고만 남으므로 여기서 반환.
             * 노드, datastore, 브리지 존재 여부는 ProxmoxPreflightValidator 가 확인.
             */
            return;
        }
        VmOptionSpec masterSpec = validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:masterInstanceType"), "masterInstanceType");
        VmOptionSpec workerSpec = validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:workerInstanceType"), "workerInstanceType");

        /*
         * 이미지 식별자는 리전별 상이, 주기적 교체. 존재하지 않는 값 전달 시 CSP 별로 다른
         * 방식으로 실패. IBM 은 pulumi-yaml 패닉으로 Go 스택 노출.
         */
        VmOptionImage osImage =
                validateImage(provider, credentialId, region, config.get("anycloud-k8s:osImage"), "osImage");

        /*
         * 인스턴스 타입과 이미지는 각각 실재해도 아키텍처가 어긋나면 조합이 불가. IBM 은
         * bx2-2x8 (amd64) 에 s390x 이미지를 지정해도 두 값 모두 목록에 있어 통과하고,
         * VPC 와 서브넷 생성 뒤 인스턴스 단계에서 "Image OS architecture s390x is not
         * supported" 로 실패. 자원 생성 전에 차단.
         */
        requireSameArchitecture(masterSpec, osImage, "masterInstanceType");
        requireSameArchitecture(workerSpec, osImage, "workerInstanceType");

        /*
         * 리전 제공 여부와 존별 제공 여부는 별개. a2-highgpu-1g 는 asia-northeast3 에 있으나
         * -a 존에는 미제공, ecs.ga1.xlarge 는 ap-northeast-2a 에 미제공. 리전 목록만으로
         * 통과시키면 VPC 와 서브넷 생성 후 인스턴스 단계에서 403.
         */
        String zone = config.get("anycloud-k8s:providerSpec.zone");
        validateZone(
                provider,
                credentialId,
                region,
                zone,
                config.get("anycloud-k8s:masterInstanceType"),
                "masterInstanceType");
        validateZone(
                provider,
                credentialId,
                region,
                zone,
                config.get("anycloud-k8s:workerInstanceType"),
                "workerInstanceType");

        if ("OpenStack".equalsIgnoreCase(provider)) {
            VmOptionImage providerImage = validateImage(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:providerSpec.imageName"),
                    "providerSpec.imageName");
            VmOptionSpec flavor = validateSpec(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:providerSpec.flavorName"),
                    "providerSpec.flavorName");
            requireSameArchitecture(flavor, providerImage, "providerSpec.flavorName");
        }
    }

    private void validateZone(
            String provider, String credentialId, String region, String zone, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return;
        }
        if (vmOptionsService.isInstanceTypeAvailableInZone(provider, credentialId, region, zone, value)) {
            return;
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                fieldName,
                value,
                "Selected instance type is not available in "
                        + (StringUtils.hasText(zone) ? zone : region + " 의 기본 존"));
    }

    /** @return 목록에서 찾은 spec. 검사를 건너뛴 경우 null 이고 아키텍처 대조 대상에서 제외. */
    private VmOptionSpec validateSpec(
            String provider, String credentialId, String region, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return null;
        }
        List<VmOptionSpec> candidates = vmOptionsService.getSpecs(provider, credentialId, region, value, false, 200);
        if (candidates.isEmpty()) {
            // CSP API 가 빈 list 를 반환하는 경우: circuit breaker fallback (CSP API 장애)
            // 또는 IAM 권한 부족. 사용자 선택을 hard reject 하기보다 Pulumi 의 실 launch 단계에 위임
            // (InvalidParameterValue 등 더 정확한 진단 받음). 정상 데이터로 못 찾는 경우는 다음 라인의
            // exists=false 분기에서 reject 유지.
            log.warn(
                    "VM spec validation skipped — listSpecs returned empty (provider={}, region={}, value={}). "
                            + "Likely CSP API unavailable; deferring to Pulumi launch.",
                    provider,
                    region,
                    value);
            return null;
        }
        VmOptionSpec matched = candidates.stream()
                .filter(item -> value.equalsIgnoreCase(item.getName()))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    fieldName,
                    value,
                    "Selected VM spec was not found for region " + region);
        }
        return matched;
    }

    /** @return 목록에서 찾은 image. 검사를 건너뛴 경우 null 이고 아키텍처 대조 대상에서 제외. */
    private VmOptionImage validateImage(
            String provider, String credentialId, String region, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return null;
        }
        VmOptionImage matched =
                findImage(vmOptionsService.getImages(provider, credentialId, region, value, null, null, 200), value);
        if (matched != null) {
            return matched;
        }
        /*
         * 키워드 검색은 이름만 대상. 화면이 선택한 값은 식별자라 OCI 의 OCID 처럼 이름과 다른
         * 형식이면 식별자를 키워드로 검색해 존재하지 않는 이미지로 판정됨.
         */
        matched = findImage(vmOptionsService.getImages(provider, credentialId, region, null, null, null, 200), value);
        if (matched != null) {
            return matched;
        }
        /*
         * 키워드 조회 결과가 비었다는 이유로 CSP 장애로 단정하면 존재하지 않는 이미지가 통과함.
         * 조회 가능 여부를 한 번 더 확인. 목록이 반환되면 해당 값이 없는 것이고, 목록도 비면
         * CSP 조회 자체가 불가능한 상황이므로 차단하지 않음.
         */
        if (vmOptionsService
                .getImages(provider, credentialId, region, null, null, null, 1)
                .isEmpty()) {
            log.warn(
                    "OS image validation skipped — 이미지 목록을 읽지 못했다 (provider={}, region={}, value={})",
                    provider,
                    region,
                    value);
            return null;
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                fieldName,
                value,
                "Selected OS image was not found for region " + region);
    }

    private VmOptionImage findImage(List<VmOptionImage> candidates, String value) {
        return candidates.stream()
                .filter(item -> value.equalsIgnoreCase(item.getName()) || value.equalsIgnoreCase(item.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * spec 과 image 의 CPU 아키텍처 대조.
     *
     * <p>한쪽이라도 아키텍처를 제공하지 않거나 {@link #ARCHITECTURE_ALIASES} 에 없는 표기면
     * 통과. 아키텍처를 채우지 않는 provider 에서 정상 조합이 차단되는 것을 막기 위함이고,
     * 조회 실패 시 Pulumi 에 위임하는 본 클래스의 다른 분기와 같은 방침.
     */
    private void requireSameArchitecture(VmOptionSpec spec, VmOptionImage image, String fieldName) {
        if (spec == null || image == null) {
            return;
        }
        String specArchitecture = canonicalArchitecture(spec.getArchitecture());
        String imageArchitecture = canonicalArchitecture(image.getArchitecture());
        if (specArchitecture == null || imageArchitecture == null || specArchitecture.equals(imageArchitecture)) {
            return;
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                fieldName,
                spec.getName(),
                "Selected VM spec (" + spec.getArchitecture() + ") and OS image " + image.getName() + " ("
                        + image.getArchitecture() + ") have different CPU architectures");
    }

    /** @return 표준 아키텍처 이름. 값이 없거나 알려지지 않은 표기면 null. */
    private static String canonicalArchitecture(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return ARCHITECTURE_ALIASES.get(value.trim().toLowerCase(Locale.ROOT));
    }
}
