package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

/**
 * providerSpec.zone 은 리전 드롭다운으로 채울 수 없다 — us-south 가 아니라 us-south-1 이다.
 *
 * <p>자유 입력으로 두면 사용자가 IBM 존 명명 규칙을 외워야 하고, 틀린 값은 프로비저닝 도중에야
 * 드러난다. 계정마다 활성 zone 이 달라 {@code {region}-1} 로 넘겨짚을 수도 없다.
 */
class IbmConfigOptionsTest extends AbstractUnitTest {

    private static final String TOKEN_JSON = "{\"access_token\":\"test-token\"}";
    private static final Map<String, String> CREDENTIALS = Map.of("IBMCLOUD_API_KEY", "test-api-key");

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final IbmVmOptionsProvider provider = new IbmVmOptionsProvider(restTemplate, new ObjectMapper());

    private void expectZones() {
        server.expect(MockRestRequestMatchers.requestTo("https://iam.cloud.ibm.com/identity/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/v1/regions/jp-tok/zones")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        """
                        {"zones":[{"name":"jp-tok-1","status":"available"},
                                  {"name":"jp-tok-2","status":"available"},
                                  {"name":"jp-tok-3","status":"impaired"}]}
                        """,
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void theZoneKeyOffersTheZonesTheAccountCanActuallyUse() {
        expectZones();

        List<String> options = provider.listConfigOptions(CREDENTIALS, "providerSpec.zone", "jp-tok");

        assertThat(options).containsExactly("jp-tok-1", "jp-tok-2");
        server.verify();
    }

    @Test
    void otherKeysAreLeftAsFreeTextWithoutCallingTheApi() {
        // 열거할 수 없는 키까지 조회하면 폼을 열 때마다 쓸데없는 왕복이 생긴다.
        assertThat(provider.listConfigOptions(CREDENTIALS, "providerSpec.resourceGroup", "jp-tok"))
                .isEmpty();
        server.verify();
    }

    @Test
    void withoutARegionThereIsNothingToOffer() {
        // 리전을 고르기 전에는 zone 을 알 수 없다. 화면은 그때까지 자유 입력으로 둔다.
        assertThat(provider.listConfigOptions(CREDENTIALS, "providerSpec.zone", null))
                .isEmpty();
        server.verify();
    }
}
