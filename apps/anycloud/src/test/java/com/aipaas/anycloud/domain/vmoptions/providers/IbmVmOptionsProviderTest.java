package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
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
 * IBM VPC 응답을 화면이 쓰는 모양으로 옮기는 부분.
 *
 * <p>VPC API 는 수치를 {@code {"type":"fixed","value":8}} 로 감싸 준다. 그대로 숫자로 읽으면 파싱이
 * 깨지고, 감싼 껍데기만 읽으면 vCPU 와 메모리가 0 으로 나가 화면이 "사양 미상"으로 보인다.
 */
class IbmVmOptionsProviderTest extends AbstractUnitTest {

    private static final String TOKEN_JSON = "{\"access_token\":\"test-token\",\"expires_in\":3600}";

    private static final Map<String, String> CREDENTIALS = Map.of("IBMCLOUD_API_KEY", "test-api-key");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final IbmVmOptionsProvider provider = new IbmVmOptionsProvider(restTemplate, mapper);

    private void expectToken() {
        server.expect(MockRestRequestMatchers.requestTo("https://iam.cloud.ibm.com/identity/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private void expectGet(String urlContains, String json) {
        server.expect(MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString(urlContains)))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer test-token"))
                .andRespond(MockRestResponseCreators.withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void regionsCarryAvailability() {
        expectToken();
        expectGet(
                "us-south.iaas.cloud.ibm.com/v1/regions",
                """
                {"regions":[
                  {"name":"jp-tok","status":"available","endpoint":"https://jp-tok.iaas.cloud.ibm.com"},
                  {"name":"eu-gb","status":"unavailable","endpoint":"https://eu-gb.iaas.cloud.ibm.com"}]}
                """);

        List<VmOptionRegion> regions = provider.listRegions(CREDENTIALS);

        assertThat(regions).extracting(VmOptionRegion::getId).containsExactly("eu-gb", "jp-tok");
        assertThat(regions).filteredOn(r -> "jp-tok".equals(r.getId())).allMatch(VmOptionRegion::getAvailable);
        assertThat(regions).filteredOn(r -> "eu-gb".equals(r.getId())).noneMatch(VmOptionRegion::getAvailable);
        server.verify();
    }

    @Test
    void profileNumbersAreUnwrapped() {
        // 감싼 값을 못 읽으면 vCPU 와 메모리가 0 으로 나가 화면이 사양을 못 보여준다.
        expectToken();
        expectGet(
                "jp-tok.iaas.cloud.ibm.com/v1/instance/profiles",
                """
                {"profiles":[{"name":"bx2-2x8","family":"balanced",
                  "vcpu_count":{"type":"fixed","value":2},
                  "vcpu_architecture":{"type":"fixed","value":"amd64"},
                  "memory":{"type":"fixed","value":8}}]}
                """);

        List<VmOptionSpec> specs = provider.listSpecs(CREDENTIALS, "jp-tok", null, false, 50);

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).getVcpu()).isEqualTo(2);
        assertThat(specs.get(0).getMemoryGb()).isEqualTo(8.0);
        assertThat(specs.get(0).getArchitecture()).isEqualTo("amd64");
        assertThat(specs.get(0).getGpuCount()).isZero();
        server.verify();
    }

    @Test
    void gpuOnlyKeepsProfilesThatActuallyHaveGpus() {
        expectToken();
        expectGet(
                "/v1/instance/profiles",
                """
                {"profiles":[
                  {"name":"bx2-2x8","vcpu_count":{"value":2},"memory":{"value":8}},
                  {"name":"gx3d-24x120x1a100p","vcpu_count":{"value":24},"memory":{"value":120},
                   "gpu_count":{"value":1},"gpu_model":{"values":["A100"]}}]}
                """);

        List<VmOptionSpec> gpu = provider.listSpecs(CREDENTIALS, "jp-tok", null, true, 50);

        assertThat(gpu).extracting(VmOptionSpec::getName).containsExactly("gx3d-24x120x1a100p");
        assertThat(gpu.get(0).getGpuCount()).isEqualTo(1);
        assertThat(gpu.get(0).getDescription()).contains("A100");
        server.verify();
    }

    @Test
    void imagesThatAreNotAvailableAreSkipped() {
        // 폐기 예정 이미지로 만들면 프로비저닝이 중간에 실패한다.
        expectToken();
        expectGet(
                "/v1/images",
                """
                {"images":[
                  {"id":"r022-a","name":"ibm-ubuntu-24-04-6-minimal-amd64-6","status":"available",
                   "visibility":"public","created_at":"2026-01-02T03:04:05Z",
                   "operating_system":{"name":"ubuntu-24-04-amd64","architecture":"amd64",
                     "vendor":"Canonical","version":"24.04 LTS","family":"Ubuntu Linux"}},
                  {"id":"r022-b","name":"ibm-ubuntu-20-04-old","status":"deprecated","visibility":"public",
                   "operating_system":{"architecture":"amd64","vendor":"Canonical"}}],
                 "total_count":2}
                """);

        List<VmOptionImage> images = provider.listImages(CREDENTIALS, "jp-tok", null, null, null, 50);

        assertThat(images).extracting(VmOptionImage::getName).containsExactly("ibm-ubuntu-24-04-6-minimal-amd64-6");
        assertThat(images.get(0).getOsType()).isEqualTo("Ubuntu Linux");
        assertThat(images.get(0).getOwner()).isEqualTo("Canonical");
        // 프로비저닝은 이름으로 이미지를 찾는다. 불투명한 id 를 넘기면 만들 수 없다.
        assertThat(images.get(0).getId()).isEqualTo("ibm-ubuntu-24-04-6-minimal-amd64-6");
        server.verify();
    }

    @Test
    void aNextLinkOnAnotherHostStopsPaging() {
        /*
         * next 는 응답 본문에서 온다. 그 URL 로 다시 요청할 때 IAM 토큰을 Bearer 로 붙이므로,
         * host 를 확인하지 않으면 응답을 조작할 수 있는 상대에게 토큰을 넘기는 경로가 된다.
         */
        expectToken();
        expectGet(
                "/v1/images",
                """
                {"images":[{"id":"r022-a","name":"ibm-ubuntu-24-04","status":"available",
                  "operating_system":{"architecture":"amd64"}}],
                 "next":{"href":"https://evil.example.com/v1/images?start=x"}}
                """);

        List<VmOptionImage> images = provider.listImages(CREDENTIALS, "jp-tok", null, null, null, 50);

        assertThat(images).hasSize(1);
        // evil.example.com 으로 두 번째 요청이 나갔다면 MockRestServiceServer 가 실패시킨다.
        server.verify();
    }

    @Test
    void theNextPageUrlGetsTheVersionBackBeforeItIsRequested() {
        // IBM 이 주는 next.href 에는 version 과 generation 이 빠져 있다. 그대로 요청하면 400 이라
        // 두 번째 페이지부터 이미지가 끊긴다. 첫 페이지만 보는 테스트로는 드러나지 않는다.
        expectToken();
        expectGet(
                "/v1/images?version=",
                """
                {"images":[{"id":"r022-a","name":"ibm-ubuntu-24-04-a","status":"available",
                  "operating_system":{"architecture":"amd64"}}],
                 "next":{"href":"https://jp-tok.iaas.cloud.ibm.com/v1/images?start=abc&limit=100"}}
                """);
        server.expect(MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("start=abc"),
                        org.hamcrest.Matchers.containsString("version="),
                        org.hamcrest.Matchers.containsString("generation="))))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(
                        """
                        {"images":[{"id":"r022-b","name":"ibm-ubuntu-24-04-b","status":"available",
                          "operating_system":{"architecture":"amd64"}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        List<VmOptionImage> images = provider.listImages(CREDENTIALS, "jp-tok", null, null, null, 50);

        assertThat(images)
                .extracting(VmOptionImage::getName)
                .containsExactly("ibm-ubuntu-24-04-a", "ibm-ubuntu-24-04-b");
        server.verify();
    }

    @Test
    void aMissingApiKeyIsReportedBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.listRegions(Map.of()))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("IBMCLOUD_API_KEY");
        server.verify();
    }

    @Test
    void specsRequireARegionBecauseTheHostIsPerRegion() {
        assertThatThrownBy(() -> provider.listSpecs(CREDENTIALS, " ", null, false, 50))
                .isInstanceOf(CustomException.class);
        server.verify();
    }

    @Test
    void aRegionThatIsNotAHostnameIsRejected() {
        // region 이 그대로 host 가 되므로 검증하지 않으면 임의 호스트로 토큰이 나간다.
        assertThatThrownBy(() -> provider.listSpecs(CREDENTIALS, "jp-tok/../evil.com", null, false, 50))
                .isInstanceOf(CustomException.class);
        server.verify();
    }

    @Test
    void zonesComeFromTheAccountNotFromAGuessedSuffix() {
        // 계정마다 활성 zone 이 달라 {region}-1 로 넘겨짚으면 만들다 실패한다.
        expectToken();
        expectGet(
                "/v1/regions/jp-tok/zones",
                """
                {"zones":[{"name":"jp-tok-1","status":"available"},
                          {"name":"jp-tok-2","status":"available"},
                          {"name":"jp-tok-3","status":"impaired"}]}
                """);

        List<String> zones = AbstractVmOptionsProvider.withCredentials(CREDENTIALS, () -> provider.listZones("jp-tok"));

        assertThat(zones).containsExactly("jp-tok-1", "jp-tok-2");
        server.verify();
    }
}
