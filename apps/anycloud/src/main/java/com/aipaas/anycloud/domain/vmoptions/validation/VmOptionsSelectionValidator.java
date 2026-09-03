package com.aipaas.anycloud.domain.vmoptions.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class VmOptionsSelectionValidator {

    private final VmOptionsQueryService vmOptionsQueryService;

    public VmOptionsSelectionValidator(VmOptionsQueryService vmOptionsQueryService) {
        this.vmOptionsQueryService = vmOptionsQueryService;
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
        validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:masterInstanceType"), "masterInstanceType");
        validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:workerInstanceType"), "workerInstanceType");

        if ("OpenStack".equalsIgnoreCase(provider)) {
            validateImage(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:openstackImageName"),
                    "openstackImageName");
            validateSpec(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:openstackFlavorName"),
                    "openstackFlavorName");
        }
    }

    private void validateSpec(String provider, String credentialId, String region, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return;
        }
        List<VmOptionSpec> candidates =
                vmOptionsQueryService.listSpecs(provider, credentialId, region, value, false, 200);
        if (candidates.isEmpty()) {
            // CSP API 가 빈 list 를 반환하는 경우 — circuit breaker fallback (CSP API 장애)
            // 또는 IAM 권한 부족. 사용자 선택을 hard reject 하기보다 Pulumi 의 실 launch 단계에 위임
            // (InvalidParameterValue 등 더 정확한 진단 받음). 정상 데이터로 못 찾는 경우는 다음 라인의
            // exists=false 분기에서 reject 유지.
            log.warn(
                    "VM spec validation skipped — listSpecs returned empty (provider={}, region={}, value={}). "
                            + "Likely CSP API unavailable; deferring to Pulumi launch.",
                    provider,
                    region,
                    value);
            return;
        }
        boolean exists = candidates.stream().anyMatch(item -> value.equalsIgnoreCase(item.getName()));
        if (!exists) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    fieldName,
                    value,
                    "Selected VM spec was not found for region " + region);
        }
    }

    private void validateImage(String provider, String credentialId, String region, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return;
        }
        List<VmOptionImage> candidates =
                vmOptionsQueryService.listImages(provider, credentialId, region, value, null, null, 200);
        if (candidates.isEmpty()) {
            log.warn(
                    "OS image validation skipped — listImages returned empty (provider={}, region={}, value={}). "
                            + "Likely CSP API unavailable; deferring to Pulumi launch.",
                    provider,
                    region,
                    value);
            return;
        }
        boolean exists = candidates.stream().anyMatch(item -> value.equalsIgnoreCase(item.getName()));
        if (!exists) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    fieldName,
                    value,
                    "Selected OS image was not found for region " + region);
        }
    }
}
