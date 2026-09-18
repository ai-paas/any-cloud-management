package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프로바이더 리전을 비워 두면 각 프로바이더가 자기 기본 리전으로 붙는다.
 *
 * <p>IBM 은 {@code us-south} 라, 도쿄를 골라도 자원은 us-south 에 생기고 존은
 * {@code missing or invalid zone} 으로 거절된다. 실패 메시지가 존을 가리켜서 리전 선택이
 * 무시됐다는 사실이 드러나지 않는다.
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
        // Proxmox 는 단일 하이퍼바이저라 리전이 없고, OpenStack 은 리소스마다 region 을 직접 받는다.
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(provider)).isNull();
    }

    @Test
    void anUnknownProviderDoesNotInventAKey() {
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey("made-up")).isNull();
        assertThat(CspCredentialPulumiConfigMapper.regionConfigKey(null)).isNull();
    }
}
