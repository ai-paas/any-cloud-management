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

    /**
     * Zone.
     *
     * @param AvailableInstanceTypes 같은 리전이라도 zone 마다 쓸 수 있는 타입이 다르다. 세대가
     *     새 zone 에만 있는 경우가 흔해, 고른 타입이 없는 zone 은 재고 없음으로 막힌다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Zone(String ZoneId, String LocalName, AvailableInstanceTypes AvailableInstanceTypes) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AvailableInstanceTypes(List<String> InstanceTypes) {}
    }

    /** DescribeAvailableResource 응답. 중첩이 깊어 필요한 것만 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailableResourceResponse(AvailableZones AvailableZones) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AvailableZones(List<AvailableZone> AvailableZone) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AvailableZone(String ZoneId, String Status, AvailableResources AvailableResources) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AvailableResources(List<AvailableResource> AvailableResource) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AvailableResource(SupportedResources SupportedResources) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SupportedResources(List<SupportedResource> SupportedResource) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SupportedResource(String Value, String Status) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ZonesResponse(Zones Zones) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Zones(List<Zone> Zone) {}
    }

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
