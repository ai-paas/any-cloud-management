package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/**
 * OCI 목록 API 의 응답 껍데기.
 *
 * <p>availabilityDomains, shapes, images 는 모두 최상위가 배열이다. {@code {"items":[...]}} 만
 * 읽으면 빈 목록이 돌아오고, 호출부는 "AD 가 없다" 처럼 자원이 없는 것처럼 보고한다. 실제 테넌시에
 * 붙이기 전까지 드러나지 않았던 경로다.
 */
class OciVmOptionsProviderListShapeTest extends AbstractUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OciVmOptionsProvider provider = new OciVmOptionsProvider(new RestTemplate(), mapper);

    @Test
    void bareArrayResponseIsRead() throws Exception {
        String json = "[{\"compartmentId\":\"ocid1.tenancy.oc1..x\",\"id\":\"ocid1.ad.oc1..y\","
                + "\"name\":\"plch:AP-TOKYO-1-AD-1\"}]";

        List<OciRecords.AvailabilityDomain> ads =
                provider.listItems(mapper.readTree(json), OciRecords.AvailabilityDomain.class);

        assertThat(ads).hasSize(1);
        assertThat(ads.get(0).name()).isEqualTo("plch:AP-TOKYO-1-AD-1");
    }

    @Test
    void itemsWrappedResponseStillWorks() throws Exception {
        // 페이지네이션을 쓰는 엔드포인트는 이 형태다. 둘 다 받아야 한다.
        String json = "{\"items\":[{\"name\":\"plch:AP-TOKYO-1-AD-1\"}]}";

        List<OciRecords.AvailabilityDomain> ads =
                provider.listItems(mapper.readTree(json), OciRecords.AvailabilityDomain.class);

        assertThat(ads).hasSize(1);
    }

    @Test
    void neitherShapeYieldsEmptyList() throws Exception {
        assertThat(provider.listItems(mapper.readTree("{}"), OciRecords.AvailabilityDomain.class))
                .isEmpty();
        assertThat(provider.listItems(null, OciRecords.AvailabilityDomain.class))
                .isEmpty();
    }
}
