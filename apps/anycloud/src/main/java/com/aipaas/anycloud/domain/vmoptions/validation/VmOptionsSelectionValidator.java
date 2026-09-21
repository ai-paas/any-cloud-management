package com.aipaas.anycloud.domain.vmoptions.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
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

    /*
     * 캐시가 걸린 쪽을 쓴다. QueryService 를 직접 부르면 preflight 마다 CSP API 를 200건씩
     * 새로 훑는다 — 생성 한 번에 수백 건이 나간다.
     */
    private final VmOptionsService vmOptionsService;

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
             * Proxmox 는 인스턴스 타입이 없다. "코어-메모리MiB" 를 사용자가 직접 정하므로 대조할
             * 목록이 없다. 내려가 봐야 빈 목록을 받고 "조회 불가" 경고만 남으므로 여기서 끊는다.
             * 노드, datastore, 브리지 존재 여부는 ProxmoxPreflightValidator 가 확인한다.
             */
            return;
        }
        validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:masterInstanceType"), "masterInstanceType");
        validateSpec(
                provider, credentialId, region, config.get("anycloud-k8s:workerInstanceType"), "workerInstanceType");

        /*
         * 이미지 식별자는 리전마다 다르고 주기적으로 갈린다. 없는 값을 그대로 보내면 CSP 마다
         * 다른 방식으로 죽는다 — IBM 은 pulumi-yaml 이 패닉해 Go 스택이 그대로 올라온다.
         */
        validateImage(provider, credentialId, region, config.get("anycloud-k8s:osImage"), "osImage");

        if ("OpenStack".equalsIgnoreCase(provider)) {
            validateImage(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:providerSpec.imageName"),
                    "providerSpec.imageName");
            validateSpec(
                    provider,
                    credentialId,
                    region,
                    config.get("anycloud-k8s:providerSpec.flavorName"),
                    "providerSpec.flavorName");
        }
    }

    private void validateSpec(String provider, String credentialId, String region, String value, String fieldName) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(region)) {
            return;
        }
        List<VmOptionSpec> candidates = vmOptionsService.getSpecs(provider, credentialId, region, value, false, 200);
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
                vmOptionsService.getImages(provider, credentialId, region, value, null, null, 200);
        boolean exists = candidates.stream()
                .anyMatch(item -> value.equalsIgnoreCase(item.getName()) || value.equalsIgnoreCase(item.getId()));
        if (exists) {
            return;
        }
        /*
         * 키워드 조회가 비었다고 CSP 장애로 단정하면 없는 이미지가 그대로 통과한다. 조회 자체가
         * 되는지 한 번 더 본다 — 목록이 나오면 그 값이 없는 것이고, 목록도 비면 CSP 를 못 읽는
         * 상황이라 여기서 막지 않는다.
         */
        if (vmOptionsService
                .getImages(provider, credentialId, region, null, null, null, 1)
                .isEmpty()) {
            log.warn(
                    "OS image validation skipped — 이미지 목록을 읽지 못했다 (provider={}, region={}, value={})",
                    provider,
                    region,
                    value);
            return;
        }
        throw new CustomException(
                ErrorCode.INVALID_INPUT_VALUE,
                fieldName,
                value,
                "Selected OS image was not found for region " + region);
    }
}
