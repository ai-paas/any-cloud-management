package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningConfigRules;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.internal.ProviderConfigSchemaServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * config schema 는 포탈이 입력 칸을 만드는 유일한 근거다.
 *
 * <p>preflight 가 요구하는 키가 schema 에 없거나 optional 로 적혀 있으면, 화면은 그 칸을 안 만들거나
 * 비워 둔 채 제출을 허용한다. 사용자는 생성 버튼을 누른 뒤에야 거절을 본다.
 */
class ProviderConfigSchemaMatchesPreflightTest extends AbstractUnitTest {

    private final ProviderConfigSchemaService schemaService = new ProviderConfigSchemaServiceImpl();

    /** master/worker count 같은 공통 검증에 걸리지 않도록 최소값을 채운 config. */
    private Map<String, String> baselineConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("anycloud-k8s:masterCount", "1");
        config.put("anycloud-k8s:workerCount", "1");
        return config;
    }

    /** preflight 가 빈 config 에 대해 지목하는 키들. 예외 메시지에만 노출된다. */
    private List<String> missingKeysFor(SupportedProvisioningProvider provider) {
        Throwable thrown =
                catchThrowable(() -> ProvisioningConfigRules.validateRequiredConfig(provider, baselineConfig()));
        if (thrown == null) {
            return List.of();
        }
        String message = String.valueOf(thrown.getMessage());
        int marker = message.indexOf(':');
        if (marker < 0) {
            return List.of();
        }
        return Arrays.stream(message.substring(marker + 1).split(","))
                .map(String::trim)
                .filter(key -> key.startsWith("anycloud-k8s:"))
                .toList();
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void everyKeyPreflightDemandsIsOfferedByTheSchema(SupportedProvisioningProvider provider) {
        List<String> demanded = missingKeysFor(provider);
        Set<String> offered = schemaService.getSchema(provider.getCanonicalName()).stream()
                .map(ProviderConfigKey::key)
                .collect(Collectors.toSet());

        assertThat(offered)
                .as("%s 의 preflight 필수 키가 config schema 에 없다", provider)
                .containsAll(demanded);
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void thoseKeysAreMarkedRequiredSoTheFormCannotSkipThem(SupportedProvisioningProvider provider) {
        List<String> demanded = missingKeysFor(provider);
        Set<String> requiredInSchema = schemaService.getSchema(provider.getCanonicalName()).stream()
                .filter(ProviderConfigKey::required)
                .map(ProviderConfigKey::key)
                .collect(Collectors.toSet());

        assertThat(requiredInSchema)
                .as("%s: preflight 는 요구하는데 schema 는 optional 로 적어 둔 키가 있다", provider)
                .containsAll(demanded);
    }

    @Test
    void openstackNeedsBothTheExternalNetworkAndTheFloatingIpPool() {
        // emitter 가 둘 다 읽는다. 하나만 필수로 두면 preflight 를 통과한 뒤 PROVISION 에서 죽는다.
        List<String> demanded = missingKeysFor(SupportedProvisioningProvider.OPENSTACK);

        assertThat(demanded)
                .contains("anycloud-k8s:providerSpec.externalNetworkId", "anycloud-k8s:providerSpec.floatingIpPool");
    }

    @Test
    void ibmNeedsAZoneNotJustARegion() {
        // providerSpec.zone 은 us-south 가 아니라 us-south-1 이다. 리전 드롭다운으로는 채울 수 없다.
        assertThat(missingKeysFor(SupportedProvisioningProvider.IBM)).contains("anycloud-k8s:providerSpec.zone");
    }
}
