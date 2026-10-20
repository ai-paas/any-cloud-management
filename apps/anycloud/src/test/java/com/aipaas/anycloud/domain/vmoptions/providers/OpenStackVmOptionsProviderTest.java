package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/**
 * 자격증명의 OS_INSECURE / OS_ENDPOINT_OVERRIDES 가 preflight 경로에서도 반영되는지 확인.
 *
 * <p>Pulumi 쪽만 반영되던 시절, 사설 CA + 사설망 OpenStack 은 PROVISION 진입 직후 PKIX 실패로
 * 죽었다. 자격증명이 값을 받아놓고 조용히 무시하는 상태였다.
 */
class OpenStackVmOptionsProviderTest extends AbstractUnitTest {

    private final RestTemplate shared = new RestTemplate();
    private final OpenStackVmOptionsProvider provider = new OpenStackVmOptionsProvider(shared, new ObjectMapper());

    private <T> T withCreds(Map<String, String> creds, java.util.function.Supplier<T> body) {
        return AbstractVmOptionsProvider.withCredentials(creds, body);
    }

    @Test
    void neutronUrl_keepsOneVersionSegment() {
        // 카탈로그가 /v2.0 까지 포함해 광고하는 배포 존재. 그대로 결합하면 404 응답.
        assertThat(OpenStackVmOptionsProvider.neutronUrl("https://os:9696/v2.0", "/networks"))
                .isEqualTo("https://os:9696/v2.0/networks");
        assertThat(OpenStackVmOptionsProvider.neutronUrl("https://os:9696/v2.0/", "/networks"))
                .isEqualTo("https://os:9696/v2.0/networks");
    }

    @Test
    void neutronUrl_addsTheVersionWhenTheCatalogOmitsIt() {
        assertThat(OpenStackVmOptionsProvider.neutronUrl("https://os:9696", "/networks"))
                .isEqualTo("https://os:9696/v2.0/networks");
    }

    @Test
    void insecureCredential_usesSeparateClientForHttps() {
        RestTemplate selected =
                withCreds(Map.of("OS_INSECURE", "true"), () -> provider.restTemplateFor("https://os.example.com:5000"));

        assertThat(selected).isNotSameAs(shared);
    }

    @Test
    void insecureCredential_reusesSameInstanceAcrossCalls() {
        // TLS context 구성 비용이 큼. 요청마다 재생성하면 커넥션 풀도 매번 폐기.
        RestTemplate first =
                withCreds(Map.of("OS_INSECURE", "true"), () -> provider.restTemplateFor("https://os.example.com:5000"));
        RestTemplate second =
                withCreds(Map.of("OS_INSECURE", "true"), () -> provider.restTemplateFor("https://os.example.com:9292"));

        assertThat(first).isSameAs(second);
    }

    @Test
    void secureCredential_keepsSharedClient() {
        // 공유 client 의 검증 비활성화 시 AWS, GCP 호출까지 동시 비활성.
        RestTemplate selected = withCreds(
                Map.of("OS_INSECURE", "false"), () -> provider.restTemplateFor("https://os.example.com:5000"));

        assertThat(selected).isSameAs(shared);
    }

    @Test
    void absentInsecureFlag_keepsSharedClient() {
        RestTemplate selected = withCreds(Map.of(), () -> provider.restTemplateFor("https://os.example.com:5000"));

        assertThat(selected).isSameAs(shared);
    }

    @Test
    void plainHttp_keepsSharedClientEvenWhenInsecure() {
        RestTemplate selected =
                withCreds(Map.of("OS_INSECURE", "true"), () -> provider.restTemplateFor("http://os.example.com:5000"));

        assertThat(selected).isSameAs(shared);
    }

    @Test
    void insecureFlag_acceptsCommonSpellings() {
        for (String truthy : new String[] {"true", "1", "yes", "ON"}) {
            RestTemplate selected = withCreds(
                    Map.of("OS_INSECURE", truthy), () -> provider.restTemplateFor("https://os.example.com:5000"));
            assertThat(selected).as("%s", truthy).isNotSameAs(shared);
        }
    }

    @Test
    void imagesUrl_doesNotDoubleVersionSegment() {
        // Pulumi override 는 /v2/ 포함 시 동작하므로 동일 값이 이 경로로도 수신.
        assertThat(OpenStackVmOptionsProvider.imagesUrl("https://proxy:9292/v2/"))
                .isEqualTo("https://proxy:9292/v2/images");
        assertThat(OpenStackVmOptionsProvider.imagesUrl("https://proxy:9292/v2"))
                .isEqualTo("https://proxy:9292/v2/images");
    }

    @Test
    void imagesUrl_addsVersionForCatalogStyleBase() {
        // 카탈로그가 제공하는 glance 주소에는 버전 미포함.
        assertThat(OpenStackVmOptionsProvider.imagesUrl("https://192.168.10.10:9292"))
                .isEqualTo("https://192.168.10.10:9292/v2/images");
        assertThat(OpenStackVmOptionsProvider.imagesUrl("https://192.168.10.10:9292/"))
                .isEqualTo("https://192.168.10.10:9292/v2/images");
    }

    @Test
    void endpointOverrides_parsedAndLowercased() {
        Map<String, String> overrides = withCreds(
                Map.of("OS_ENDPOINT_OVERRIDES", "{\"Compute\":\"https://proxy:8774/v2.1/\"}"),
                provider::endpointOverrides);

        assertThat(overrides).containsEntry("compute", "https://proxy:8774/v2.1/");
    }

    @Test
    void endpointOverrides_absentYieldsEmpty() {
        assertThat(withCreds(Map.of(), provider::endpointOverrides)).isEmpty();
    }

    @Test
    void endpointOverrides_malformedFallsBackToCatalog() {
        // 깨진 override 로 preflight 를 통째로 실패시키면 카탈로그가 멀쩡한 배포까지 막힌다.
        Map<String, String> overrides =
                withCreds(Map.of("OS_ENDPOINT_OVERRIDES", "compute=https://proxy"), provider::endpointOverrides);

        assertThat(overrides).isEmpty();
    }
}
