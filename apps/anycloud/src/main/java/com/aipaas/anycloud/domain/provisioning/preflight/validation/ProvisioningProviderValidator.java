package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProvisioningProviderValidator {

    private final com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties pulumiProperties;
    private final VmOptionsSelectionValidator vmOptionsSelectionValidator;
    private final MeterRegistry meterRegistry;

    public ProvisioningRequest validateAndBuildRequest(ProvisionClusterRequest cluster) {
        return validateAndBuildRequest(
                cluster, ResolvedCspCredential.builder().environment(Map.of()).build());
    }

    /**
     * Static + live 한 번에 — 옛 동작 보존용 wrapper. 신규 caller 는
     * {@link #validateStaticAndBuildRequest} / {@link #validateLive} 분리 호출 권장.
     */
    public ProvisioningRequest validateAndBuildRequest(
            ProvisionClusterRequest cluster, ResolvedCspCredential credential) {
        ProvisioningRequest request = validateStaticAndBuildRequest(cluster, credential);
        validateLive(request);
        return request;
    }

    /**
     * In-memory 정적 검증만 수행 — provider name, required config keys, credential value 존재.
     * Network 호출 없음 (~ms). controller sync 경로에서 호출 안전.
     *
     * <p>Live 검증 (instance type / image 존재 여부 — CSP API) 은
     * {@link #validateLive} 로 분리. 잘못된 instance type 도 여기선 통과 — async worker 에서 잡힌다.
     */
    public ProvisioningRequest validateStaticAndBuildRequest(
            ProvisionClusterRequest cluster, ResolvedCspCredential credential) {
        SupportedProvisioningProvider provider = normalizeProvider(cluster.getClusterProvider());
        Map<String, String> config = normalizeConfig(cluster);
        ProvisioningConfigRules.applyDefaults(provider, config);

        ProvisioningConfigRules.validateRequiredConfig(provider, config);
        ProvisioningCredentialRules.validateCredentialValues(
                provider, credential.environmentOrEmpty(), pulumiProperties);

        return ProvisioningRequest.builder()
                .provider(provider.getCanonicalName())
                .clusterName(cluster.getClusterName())
                .environment(cluster.getEnvironment())
                .region(cluster.getRegion())
                .credentialId(credential.getCredentialId())
                .credentialName(credential.getCredentialName())
                .config(config)
                .credentialEnvironment(credential.environmentOrEmpty())
                .build();
    }

    /**
     * Live API 호출로 selection (instance type / image) 존재 여부 확인. CSP SDK call 발생 (수 초).
     * 반드시 async worker (provision step 의 첫 단계) 에서만 호출 — controller sync 경로 금지.
     *
     * <p>영속화된 {@link ProvisioningRequest} 만으로 동작 (worker 재실행 / retry 시 원본
     * {@code ProvisionClusterRequest} 가 살아있지 않을 수 있음).
     */
    public void validateLive(ProvisioningRequest request) {
        SupportedProvisioningProvider provider = normalizeProvider(request.getProvider());
        // Timer 로 provider 별 latency 측정 — p50/p95 SLO 모니터링. outcome tag 로 success/failure
        // 분리 — failure spike 시 alerting (CSP API 장애 + cred 잘못 둘 다 잡힘).
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            ProvisioningSelectionRules.validateSelections(
                    provider,
                    vmOptionsSelectionValidator,
                    request.getCredentialId(),
                    request.getRegion(),
                    request.getConfig());
        } catch (RuntimeException e) {
            outcome = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder("anycloud.csp.validate_live")
                    .description("Live CSP validation (DescribeInstanceTypes / DescribeImages 등) latency")
                    .tag("provider", provider.getCanonicalName())
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    public static SupportedProvisioningProvider normalizeProvider(String rawProvider) {
        try {
            return SupportedProvisioningProvider.from(rawProvider);
        } catch (IllegalArgumentException e) {
            throw new CustomException(e.getMessage(), ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    public static void validateRequiredConfig(SupportedProvisioningProvider provider, Map<String, String> config) {
        ProvisioningConfigRules.validateRequiredConfig(provider, config);
    }

    public static void validateCredentials(
            SupportedProvisioningProvider provider,
            com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties pulumiProperties) {
        ProvisioningCredentialRules.validateCredentials(provider, pulumiProperties);
    }

    public static void validateCredentialValues(
            SupportedProvisioningProvider provider, Map<String, String> providedCredentials) {
        ProvisioningCredentialRules.validateCredentialValues(provider, providedCredentials);
    }

    public static List<String> requiredCredentialKeys(SupportedProvisioningProvider provider) {
        return ProvisioningCredentialRules.requiredCredentialKeys(provider);
    }

    public static void validateCredentialValues(
            SupportedProvisioningProvider provider,
            Map<String, String> providedCredentials,
            com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties pulumiProperties) {
        ProvisioningCredentialRules.validateCredentialValues(provider, providedCredentials, pulumiProperties);
    }

    public static boolean hasEnvValue(
            String key, com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties pulumiProperties) {
        return ProvisioningCredentialRules.hasEnvValue(key, pulumiProperties);
    }

    public static boolean hasCredentialValue(
            String key,
            Map<String, String> providedCredentials,
            com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties pulumiProperties) {
        return ProvisioningCredentialRules.hasCredentialValue(key, providedCredentials, pulumiProperties);
    }

    public static Map<String, String> normalizeConfig(ProvisionClusterRequest cluster) {
        return cluster.getConfig() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cluster.getConfig());
    }

    public static void validateSelections(
            ProvisionClusterRequest cluster,
            SupportedProvisioningProvider provider,
            VmOptionsSelectionValidator vmOptionsSelectionValidator,
            Map<String, String> config) {
        ProvisioningSelectionRules.validateSelections(cluster, provider, vmOptionsSelectionValidator, config);
    }
}
