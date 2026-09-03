package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Alibaba ECS OpenAPI 응답의 typed projection. RPC 스타일이라 응답이 중첩됨:
 * {@code {"Regions":{"Region":[...]}, "RequestId":...}}.
 */
final class AlibabaRecords {

    private AlibabaRecords() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Region(String RegionId, String LocalName, String RegionEndpoint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstanceType(
            String InstanceTypeId,
            String InstanceTypeFamily,
            String InstanceTypeFamilyLevel,
            Integer CpuCoreCount,
            Double MemorySize,
            String GPUAmount,
            String CpuArchitecture) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String ImageId,
            String ImageName,
            String Architecture,
            String ImageOwnerAlias,
            Boolean IsPublic,
            String CreationTime) {}

    // Wrapper containers — Alibaba 의 nested {Regions:{Region:[]}} 패턴.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionsResponse(Regions Regions) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Regions(List<Region> Region) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstanceTypesResponse(InstanceTypes InstanceTypes) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record InstanceTypes(List<InstanceType> InstanceType) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImagesResponse(Images Images) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Images(List<Image> Image) {}
    }
}
