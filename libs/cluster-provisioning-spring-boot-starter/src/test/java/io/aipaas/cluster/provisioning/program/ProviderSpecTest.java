package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CSP 전용 설정이 provider 별로 격리되는지 확인.
 *
 * <p>평면 접두 키는 provider=aws 인데 openstack 값을 채워도 통과시켰다. 중첩 구조는 그 조합을
 * 애초에 만들 수 없게 한다.
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
    void fallsBackToLegacyFlatKeys() {
        // 저장된 요청 페이로드와 Bruno 환경이 아직 평면 키를 쓴다.
        ProviderSpec spec = ProviderSpec.from(
                "openstack", cfg("openstackImageName", "ubuntu-24.04", "openstackFloatingIpPool", "external"));

        ProviderSpec.Openstack os = (ProviderSpec.Openstack) spec;
        assertThat(os.imageName()).isEqualTo("ubuntu-24.04");
        assertThat(os.floatingIpPool()).isEqualTo("external");
    }

    @Test
    void nestedKeyWinsOverLegacy() {
        ProviderSpec spec =
                ProviderSpec.from("openstack", cfg("providerSpec.imageName", "new", "openstackImageName", "old"));

        assertThat(((ProviderSpec.Openstack) spec).imageName()).isEqualTo("new");
    }

    @Test
    void otherProvidersSpecKeysAreIgnored() {
        // provider=gcp 요청에 openstack 값이 섞여 있어도 gcp spec 에 스며들지 않는다.
        ProviderSpec spec =
                ProviderSpec.from("gcp", cfg("providerSpec.project", "p-1", "openstackFloatingIpPool", "external"));

        assertThat(spec).isInstanceOf(ProviderSpec.Gcp.class);
        assertThat(((ProviderSpec.Gcp) spec).project()).isEqualTo("p-1");
    }

    @Test
    void providersWithoutOwnConfigYieldNull() {
        // AWS / DigitalOcean 은 전용 설정이 없다. 빈 record 를 만들면 의미 없는 분기가 생긴다.
        assertThat(ProviderSpec.from("aws", cfg())).isNull();
        assertThat(ProviderSpec.from("digitalocean", cfg())).isNull();
    }

    @Test
    void nullProviderYieldsNull() {
        assertThat(ProviderSpec.from(null, cfg())).isNull();
    }

    @Test
    void canonicalizesProviderName() {
        // API 는 "OpenStack" 처럼 표기가 섞여 들어온다.
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
