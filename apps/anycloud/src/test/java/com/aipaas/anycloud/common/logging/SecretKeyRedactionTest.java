package com.aipaas.anycloud.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 키 이름으로 가린다.
 *
 * <p>기존 규칙은 값의 생김새만 본다. AWS 시크릿 키처럼 형식이 없는 값은 그대로 새어 나간다.
 * 어떤 값인지는 옆에 붙은 키 이름이 말해준다.
 */
class SecretKeyRedactionTest extends AbstractUnitTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "AWS_SECRET_ACCESS_KEY",
                "ARM_CLIENT_SECRET",
                "OS_PASSWORD",
                "PROXMOX_VE_API_TOKEN",
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
        // 전부 가리면 작업 이력을 봐도 무엇을 한 작업인지 알 수 없다.
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
