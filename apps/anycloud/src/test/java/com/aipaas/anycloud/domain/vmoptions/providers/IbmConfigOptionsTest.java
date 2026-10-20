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
 * providerSpec.zone 은 리전 드롭다운으로 채울 수 없음. us-south 가 아닌 us-south-1.
 *
 * <p>자유 입력으로 두면 사용자가 IBM 존 명명 규칙을 직접 입력해야 하고, 잘못된 값은 프로비저닝
 * 단계에서 드러남. 계정별 활성 zone 이 달라 {@code {region}-1} 추정도 불가.
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
    void theResourceGroupKeyOffersTheGroupsWithTheirNames() {
        /*
         * 그룹 ID 는 콘솔 확인이 필요한 값. 계정에서 조회해 이름과 함께 제공.
         * ID 만 전달하면 화면에 식별자가 그대로 노출돼 선택 대상 식별 불가.
         */
        server.expect(MockRestRequestMatchers.requestTo("https://iam.cloud.ibm.com/identity/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        server.expect(MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("resource-controller.cloud.ibm.com/v2/resource_groups")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        """
                        {"resources":[{"id":"g-1","name":"Default","state":"ACTIVE","default":true},
                                      {"id":"g-2","name":"team-a","state":"ACTIVE"},
                                      {"id":"g-3","name":"old","state":"REMOVED"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(provider.listConfigOptionsWithLabels(CREDENTIALS, "providerSpec.resourceGroup", "jp-tok"))
                .extracting(
                        com.aipaas.anycloud.domain.vmoptions.api.ConfigOption::value,
                        com.aipaas.anycloud.domain.vmoptions.api.ConfigOption::label)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("g-1", "Default (기본)"),
                        org.assertj.core.api.Assertions.tuple("g-2", "team-a"));
        server.verify();
    }

    @Test
    void otherKeysAreLeftAsFreeTextWithoutCallingTheApi() {
        // 열거 불가 키까지 조회하면 폼 진입마다 불필요한 왕복 발생.
        assertThat(provider.listConfigOptions(CREDENTIALS, "providerSpec.somethingElse", "jp-tok"))
                .isEmpty();
        server.verify();
    }

    @Test
    void withoutARegionThereIsNothingToOffer() {
        // 리전 선택 전에는 zone 확인 불가. 그 시점까지 화면은 자유 입력 유지.
        assertThat(provider.listConfigOptions(CREDENTIALS, "providerSpec.zone", null))
                .isEmpty();
        server.verify();
    }
}
