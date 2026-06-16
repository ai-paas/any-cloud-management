package com.aipaas.anycloud.domain.vmoptions.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
