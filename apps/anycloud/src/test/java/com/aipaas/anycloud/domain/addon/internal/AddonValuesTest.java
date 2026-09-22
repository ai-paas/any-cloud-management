package com.aipaas.anycloud.domain.addon.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AddonValuesTest {

    @Test
    void yamlWithCommentsBecomesJson() {
        String json = AddonValues.toJson(
                """
                # driver 는 operator 가 설치한다
                driver:
                  enabled: true
                dcgmExporter:
                  enabled: false
                """);

        assertThat(json).isEqualTo("{\"driver\":{\"enabled\":true},\"dcgmExporter\":{\"enabled\":false}}");
    }

    @Test
    void jsonIsLeftAlone() {
        assertThat(AddonValues.toJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(AddonValues.toJson(null)).isNull();
        assertThat(AddonValues.toJson("  ")).isEqualTo("  ");
    }
}
