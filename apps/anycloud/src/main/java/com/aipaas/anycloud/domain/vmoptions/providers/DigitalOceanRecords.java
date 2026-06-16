package com.aipaas.anycloud.domain.vmoptions.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** DigitalOcean API v2 응답 typed projection. */
final class DigitalOceanRecords {

    private DigitalOceanRecords() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Region(String slug, String name, Boolean available, List<String> features, List<String> sizes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Size(
            String slug, String description, Integer vcpus, Integer memory, Boolean available, List<String> regions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Image(
            String id,
            String name,
            String slug,
            String distribution,
            @JsonProperty("public") Boolean isPublic,
            @JsonProperty("created_at") String createdAt,
            List<String> regions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionsResponse(List<Region> regions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SizesResponse(List<Size> sizes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImagesResponse(List<Image> images) {}
}
