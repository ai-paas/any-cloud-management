package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import java.util.Map;

public final class ProvisioningSelectionRules {

    private ProvisioningSelectionRules() {}

    public static void validateSelections(
            ProvisionClusterRequest cluster,
            SupportedProvisioningProvider provider,
            VmOptionsSelectionValidator vmOptionsSelectionValidator,
            Map<String, String> config) {
        validateSelections(
                provider, vmOptionsSelectionValidator, cluster.getCredentialId(), cluster.getRegion(), config);
    }

    /**
     * Worker step 에서 호출되는 overload — {@link ProvisionClusterRequest} 의존성 없이
     * 영속화된 {@link io.aipaas.cluster.provisioning.api.ProvisioningRequest}
     * 의 field 만으로 동작.
     */
    public static void validateSelections(
            SupportedProvisioningProvider provider,
            VmOptionsSelectionValidator vmOptionsSelectionValidator,
            String credentialId,
            String region,
            Map<String, String> config) {
        // fix: credentialId 를 validator 에 전달해 live spec 조회가 사용자 등록 credential
        // 로 진행. 이전에는 null 전달 → host env fallback → 비어있는 list → false-negative
        // "not found" 에러.
        vmOptionsSelectionValidator.validateSelections(provider.getCanonicalName(), credentialId, region, config);
    }
}
