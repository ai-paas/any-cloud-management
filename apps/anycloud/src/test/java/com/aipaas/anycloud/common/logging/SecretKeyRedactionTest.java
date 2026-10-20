package com.aipaas.anycloud.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 키 이름 기준 마스킹.
 *
 * <p>기존 규칙은 값의 형식만 판정. AWS 시크릿 키처럼 고정 형식이 없는 값은 미차단.
 * 값의 종류는 인접한 키 이름이 제공.
 */
class SecretKeyRedactionTest extends AbstractUnitTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "AWS_SECRET_ACCESS_KEY",
                "ARM_CLIENT_SECRET",
                "OS_PASSWORD",
                "PROXMOX_VE_API_TOKEN_SECRET",
                "IBMCLOUD_API_KEY",
                "TF_VAR_private_key",
                "password",
                "clientSecret",
            })
    void masksValuesOfSecretishKeys(String key) {
        String json = "{\"" + key + "\":\"s3cr3t-value-that-should-not-leak\"}";

        String redacted = SensitiveDataRedactor.redact(json);

        assertThat(redacted).doesNotContain("s3cr3t-value-that-should-not-leak");
        assertThat(redacted).as("키 이름은 남아야 어떤 값이 가려졌는지 안다").contains(key);
    }

    @Test
    void leavesOrdinaryFieldsAlone() {
        // 전체 마스킹 시 작업 이력에서 작업 내용 확인 불가.
        String json = "{\"clusterName\":\"demo\",\"region\":\"ap-tokyo-1\",\"workerCount\":2}";

        assertThat(SensitiveDataRedactor.redact(json)).isEqualTo(json);
    }

    @Test
    void isIdempotent() {
        String once = SensitiveDataRedactor.redact("{\"password\":\"abcd1234\"}");

        assertThat(SensitiveDataRedactor.redact(once)).isEqualTo(once);
    }

    @Test
    void handlesSpacingAroundTheColon() {
        assertThat(SensitiveDataRedactor.redact("{\"password\" : \"abcd1234\"}"))
                .doesNotContain("abcd1234");
    }
}
