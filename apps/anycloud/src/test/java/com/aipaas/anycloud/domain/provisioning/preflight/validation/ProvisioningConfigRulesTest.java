package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * UX 개선 (#1, #4) 회귀 보호:
 *
 * <ul>
 *   <li>#1 Boolean strict — "True"/"1"/"yes" 같은 비정형 입력은 silent false 가 아닌 400.</li>
 *   <li>#4 masterCount 메시지 휴머나이즈 — etcd 모르는 사용자도 권장값 알 수 있어야 함.</li>
 * </ul>
 */
class ProvisioningConfigRulesTest extends AbstractUnitTest {

    private Map<String, String> baseAwsConfig() {
        Map<String, String> config = new HashMap<>();
        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.AWS, config);
        return config;
    }

    // -- #1 Boolean strict parser ----------------------------------------------------------

    @Test
    void booleanFlags_accept_lowercaseTrue() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:enableIngress", "true");
        config.put("anycloud-k8s:enableGpuOperator", "false");
        config.put("anycloud-k8s:dbEnabled", "TRUE"); // case-insensitive 허용.

        // validation should not throw.
        ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config);
    }

    @Test
    void booleanFlags_reject_typoTrue() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:enableIngress", "True!"); // 오타.

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("enableIngress")
                .hasMessageContaining("'True!'")
                .hasMessageContaining("'true' or 'false'");
    }

    @Test
    void booleanFlags_reject_numericOne() {
        // "1" / "yes" 등은 boolean 의미로 흔히 쓰지만 silent false 위험 → 400.
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:enableGpuOperator", "1");

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("enableGpuOperator");
    }

    @Test
    void booleanFlags_reject_yes() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:dbEnabled", "yes");

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("dbEnabled");
    }

    @Test
    void booleanFlags_unsetKey_allowedAndDefaultsApplied() {
        // applyDefaults 가 false 를 putIfAbsent — 사용자가 명시 안 하면 통과.
        Map<String, String> config = baseAwsConfig();

        ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config);

        assertThat(config.get("anycloud-k8s:enableIngress")).isEqualTo("false");
        assertThat(config.get("anycloud-k8s:enableGpuOperator")).isEqualTo("false");
    }

    // -- #4 masterCount message humanization -----------------------------------------------

    @Test
    void masterCount_invalidEvenNumber_messageSuggestsOddRecommendation() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:masterCount", "2");

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1, 3, 5, 7")
                .hasMessageContaining("권장: 3");
    }

    @Test
    void masterCount_outOfRange_includesRecommendation() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:masterCount", "99");

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1~7")
                .hasMessageContaining("권장");
    }

    @Test
    void masterCount_notInteger_explainsAllowedValues() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:masterCount", "three");

        assertThatThrownBy(
                        () -> ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("정수")
                .hasMessageContaining("1, 3, 5, 7");
    }

    @Test
    void masterCount_validOdd_doesNotThrow() {
        Map<String, String> config = baseAwsConfig();
        config.put("anycloud-k8s:masterCount", "3");

        ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.AWS, config);
    }
}
