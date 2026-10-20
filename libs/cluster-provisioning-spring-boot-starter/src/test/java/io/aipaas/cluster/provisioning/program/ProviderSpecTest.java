package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CSP 전용 설정이 provider 별로 격리되는지 확인.
 *
 * <p>평면 접두 키는 provider=aws 상태에서 openstack 값을 채워도 통과. 중첩 구조는 해당 조합의
 * 생성 자체를 차단.
 */
class ProviderSpecTest {

    private Map<String, String> cfg(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void readsNestedKeys() {
        ProviderSpec spec = ProviderSpec.from(
                "openstack", cfg("providerSpec.imageName", "ubuntu-22.04", "providerSpec.flavorName", "4-8-50"));

        assertThat(spec).isInstanceOf(ProviderSpec.Openstack.class);
        ProviderSpec.Openstack os = (ProviderSpec.Openstack) spec;
        assertThat(os.imageName()).isEqualTo("ubuntu-22.04");
        assertThat(os.flavorName()).isEqualTo("4-8-50");
    }

    @Test
    void legacyFlatKeysAreIgnored() {
        // 평면 키는 provider 조합 검증이 불가해 제거. 무음 통과 금지.
        ProviderSpec spec =
                ProviderSpec.from("openstack", cfg("openstackImageName", "ubuntu-24.04", "gcpProject", "p"));

        ProviderSpec.Openstack os = (ProviderSpec.Openstack) spec;
        assertThat(os.imageName()).isNull();
        assertThat(os.floatingIpPool()).isNull();
    }

    @Test
    void otherProvidersSpecKeysAreIgnored() {
        // provider=gcp 요청에 openstack 값이 혼입돼도 gcp spec 에 미반영.
        ProviderSpec spec =
                ProviderSpec.from("gcp", cfg("providerSpec.project", "p-1", "providerSpec.floatingIpPool", "external"));

        assertThat(spec).isInstanceOf(ProviderSpec.Gcp.class);
        assertThat(((ProviderSpec.Gcp) spec).project()).isEqualTo("p-1");
    }

    @Test
    void providersWithoutOwnConfigYieldNull() {
        // AWS 는 전용 설정 부재, 미등록 provider 도 동일. 빈 record 생성은 불필요한 분기 추가.
        assertThat(ProviderSpec.from("aws", cfg())).isNull();
        assertThat(ProviderSpec.from("digitalocean", cfg())).isNull();
    }

    @Test
    void nullProviderYieldsNull() {
        assertThat(ProviderSpec.from(null, cfg())).isNull();
    }

    @Test
    void canonicalizesProviderName() {
        // API 는 "OpenStack" 처럼 대소문자가 혼재한 표기로 수신.
        assertThat(ProviderSpec.from("OpenStack", cfg("providerSpec.imageName", "x")))
                .isInstanceOf(ProviderSpec.Openstack.class);
    }

    @Test
    void clusterSpecCarriesProviderSpec() {
        ClusterSpec spec = ClusterSpec.from(cfg(
                "provider", "openstack",
                "anycloud-k8s:providerSpec.floatingIpPool", "external"));

        assertThat(((ProviderSpec.Openstack) spec.providerSpec()).floatingIpPool())
                .isEqualTo("external");
    }
}
