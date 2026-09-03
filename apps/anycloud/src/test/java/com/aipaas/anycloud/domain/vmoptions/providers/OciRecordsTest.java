package com.aipaas.anycloud.domain.vmoptions.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * L1 — OciRecords 가 OCI API 응답을 안전하게 deserialize 하는지 회귀 방지.
 * <ul>
 *   <li>알려진 field 는 record 에 정확히 매핑</li>
 *   <li>알 수 없는 field 는 silently 무시 (Jackson @JsonIgnoreProperties)</li>
 *   <li>optional field 누락 → null (not exception)</li>
 * </ul>
 */
class OciRecordsTest extends AbstractUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void regionSubscription_deserialize_withAllFields() throws Exception {
        String json = "{\"regionName\":\"ap-seoul-1\",\"regionKey\":\"ICN\",\"status\":\"READY\","
                + "\"isHomeRegion\":true,\"unknownFutureField\":42}";
        var sub = mapper.readValue(json, OciRecords.RegionSubscription.class);
        assertThat(sub.regionName()).isEqualTo("ap-seoul-1");
        assertThat(sub.regionKey()).isEqualTo("ICN");
        assertThat(sub.status()).isEqualTo("READY");
    }

    @Test
    void shape_deserialize_flexShape() throws Exception {
        String json = "{\"shape\":\"VM.Standard.E4.Flex\",\"ocpus\":2,\"memoryInGBs\":16.0,"
                + "\"processorDescription\":\"AMD EPYC\","
                + "\"shapeConfigOptions\":{\"ocpuOptions\":[{\"min\":1,\"max\":64}]}}";
        var shape = mapper.readValue(json, OciRecords.Shape.class);
        assertThat(shape.shape()).isEqualTo("VM.Standard.E4.Flex");
        assertThat(shape.ocpus()).isEqualTo(2);
        assertThat(shape.memoryInGBs()).isEqualTo(16.0);
        assertThat(shape.gpus()).isNull(); // 없으면 null
        assertThat(shape.shapeConfigOptions()).isNotNull();
    }

    @Test
    void shape_deserialize_fixedShape_missingFields() throws Exception {
        String json = "{\"shape\":\"VM.Standard2.1\"}";
        var shape = mapper.readValue(json, OciRecords.Shape.class);
        assertThat(shape.shape()).isEqualTo("VM.Standard2.1");
        assertThat(shape.ocpus()).isNull();
        assertThat(shape.memoryInGBs()).isNull();
        assertThat(shape.gpus()).isNull();
        assertThat(shape.shapeConfigOptions()).isNull();
    }

    @Test
    void image_deserialize_withAllFields() throws Exception {
        String json = "{\"id\":\"ocid1.image.oc1..aaa\",\"displayName\":\"Oracle-Linux-9.4\","
                + "\"lifecycleState\":\"AVAILABLE\",\"operatingSystem\":\"Oracle Linux\","
                + "\"operatingSystemVersion\":\"9\",\"timeCreated\":\"2024-08-15T12:34:56.789Z\","
                + "\"agentFeatures\":{\"isMonitoringSupported\":true}}";
        var img = mapper.readValue(json, OciRecords.Image.class);
        assertThat(img.id()).isEqualTo("ocid1.image.oc1..aaa");
        assertThat(img.displayName()).isEqualTo("Oracle-Linux-9.4");
        assertThat(img.lifecycleState()).isEqualTo("AVAILABLE");
        assertThat(img.operatingSystem()).isEqualTo("Oracle Linux");
    }

    @Test
    void availabilityDomain_deserialize() throws Exception {
        var ad = mapper.readValue(
                "{\"name\":\"AD-1\",\"compartmentId\":\"ocid1.tenancy...\"}", OciRecords.AvailabilityDomain.class);
        assertThat(ad.name()).isEqualTo("AD-1");
    }
}
