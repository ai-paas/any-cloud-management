package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프로바이더 리전 미지정 시 각 프로바이더가 자체 기본 리전으로 접속.
 *
 * <p>IBM 기본값은 {@code us-south} 이므로 도쿄를 선택해도 자원은 us-south 에 생성되고 존은
 * {@code missing or invalid zone} 거절. 실패 메시지가 존을 지목해 리전 선택 무시 사실이
 * 드러나지 않음.
 */
class ProviderRegionConfigTest {

    @ParameterizedTest
    @CsvSource({
        "aws, aws:region",
        "gcp, gcp:region",
        "azure, azure:location",
        "alibaba, alicloud:region",
        "oci, oci:region",
        "ibm, ibm:region"
    })
    void providersThatTakeARegionReportTheirConfigKey(String provider, String expected) {
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(provider)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS", "Ibm", "OCI"})
    void theProviderNameIsMatchedWithoutCaringAboutCase(String provider) {
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(provider)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxmox", "openstack", "digitalocean"})
    void providersWithoutAProviderLevelRegionReportNothing(String provider) {
        // Proxmox 는 단일 하이퍼바이저라 리전 부재. OpenStack 은 리소스별로 region 을 직접 수신.
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(provider)).isNull();
    }

    @Test
    void anUnknownProviderDoesNotInventAKey() {
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey("made-up")).isNull();
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(null)).isNull();
    }
}
