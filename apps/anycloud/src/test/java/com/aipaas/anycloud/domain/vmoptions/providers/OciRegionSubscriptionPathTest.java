package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 구독 리전 조회 경로.
 *
 * <p>OCI 는 {@code /tenancies/{tenancyId}/regionSubscriptions} 로 받는다.
 * {@code /regionSubscriptions/{tenancyId}} 는 404 NotAuthorizedOrNotFound 를 돌려주는데,
 * 메시지가 권한 문제처럼 읽혀 자격증명을 의심하게 만든다. 실제 테넌시에 붙여 보고 알았다.
 */
class OciRegionSubscriptionPathTest extends AbstractUnitTest {

    private final OciVmOptionsProvider provider = new OciVmOptionsProvider(new RestTemplate(), new ObjectMapper());

    @Test
    void usesTheTenancyScopedPath() {
        String url = (String) ReflectionTestUtils.invokeMethod(
                provider, "regionSubscriptionsUrl", "ap-tokyo-1", "ocid1.tenancy.oc1..demo");

        assertThat(url)
                .isEqualTo("https://identity.ap-tokyo-1.oraclecloud.com/20160918/tenancies/"
                        + "ocid1.tenancy.oc1..demo/regionSubscriptions");
    }

    @Test
    void doesNotUseTheFlatPath() {
        String url = (String) ReflectionTestUtils.invokeMethod(
                provider, "regionSubscriptionsUrl", "ap-tokyo-1", "ocid1.tenancy.oc1..demo");

        assertThat(url).doesNotContain("/20160918/regionSubscriptions/ocid1");
    }
}
