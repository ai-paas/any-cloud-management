package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * IBM Cloud IAM 과 VPC API 응답 중 화면이 쓰는 필드만.
 *
 * <p>VPC API 는 수치 속성을 {@code {"type":"fixed","value":8}} 처럼 감싸서 준다. 프로파일마다
 * type 이 fixed / range / dependent 로 달라 value 가 아예 없을 수 있으므로 전부 nullable 이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class IbmRecords {

    private IbmRecords() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionList(List<Region> regions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Region(String name, String status, String endpoint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ZoneList(List<Zone> zones) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Zone(String name, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfileList(List<Profile> profiles) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(
            String name,
            String family,
            @JsonProperty("vcpu_count") IntValue vcpuCount,
            @JsonProperty("vcpu_architecture") StringValue vcpuArchitecture,
            IntValue memory,
            @JsonProperty("gpu_count") IntValue gpuCount,
            @JsonProperty("gpu_model") StringValues gpuModel) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IntValue(Integer value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StringValue(String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StringValues(List<String> values) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageList(List<Image> images, Next next, @JsonProperty("total_count") Integer totalCount) {}

    /** 다음 페이지 링크. 응답 본문에서 오므로 host 를 확인하고 써야 한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Next(String href) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String id,
            String name,
            String status,
            String visibility,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("operating_system") OperatingSystem operatingSystem) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OperatingSystem(String name, String architecture, String vendor, String version, String family) {}
}
